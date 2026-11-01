package com.im.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.ai.entity.AiGuard;
import com.im.ai.entity.AiUsage;
import com.im.ai.mapper.AiGuardMapper;
import com.im.ai.mapper.AiUsageMapper;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.util.RedisUtil;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 大模型用量护栏（四期第六批）：付费 AI 能力的闸门与账本。
 *
 * <h2>为什么闸门收在 im-ai 而不是各控制器</h2>
 *
 * <p>所有按量计费的模型调用最终都汇到本模块的三个出口
 * （{@code DashScopeChatClient.streamChat} / {@code AudioService.transcribe} /
 * {@code AudioService.synthesize}），聊天侧的「转文字」也经 SPI 落进 {@code AudioService}。
 * 闸门与记账收在出口上，新加一个 AI 功能想绕开管控，就得刻意绕开这三个方法本身——
 * 这比「记得在每个新控制器上补注解」可靠得多。
 *
 * <h2>三道判定，顺序即成本</h2>
 *
 * <p>①总开关（读内存单例）→ ②admin 豁免（角色查询，自带缓存）→ ③封禁与预算
 * （一次主键查 + 一次 Redis 读）。最便宜的先跑，被放行的常见路径（admin、开关关）
 * 一次数据库都不碰。预算口径是<b>累计</b>模型调用耗时（毫秒），只扣成功调用，
 * 理由见 {@code ImProperties.AiGuard}。
 *
 * <h2>账本失败不拦路，也不发善心</h2>
 *
 * <p>{@link #recordUsage} 落库或累加失败只记 warn：护栏是增强，不该让「流水表暂时写不进」
 * 演变成面试功能整体不可用。同理 {@link #checkAllowed} 里封禁查询与预算重算异常时
 * 按「未封禁 / 已用量 0」放行（fail-open）——表还没建就先上了新代码，不能因此把
 * 所有人关在门外；代价是极端情况下多烧几秒额度，这个代价比停机好。
 *
 * <p>Redis 计数器 {@code im:ai:used:{userId}} 是缓存不是真相：key 丢了就从
 * {@code im_ai_usage} 全量 SUM 重建（一人几百行的 SUM，走 {@code idx_user} 索引，
 * 频率是「每次 Redis miss」而不是「每次调用」）。重建写回用原子 max 而不是 set，
 * 不会把并发跑完的那笔增量盖掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGuardService {

    /** 面试对话（SSE 流式，qwen 系列） */
    public static final String SCENE_INTERVIEW_CHAT = "interview_chat";
    /** 面试「按住说话」的语音转文字 */
    public static final String SCENE_INTERVIEW_ASR = "interview_asr";
    /** 电话面试的文字合成朗读 */
    public static final String SCENE_INTERVIEW_TTS = "interview_tts";
    /** 聊天语音消息转文字（经 DashScopeSpeechToTextSpi 落到同一个 ASR 模型） */
    public static final String SCENE_MESSAGE_TO_TEXT = "message_to_text";

    /** 已用毫秒计数器；不带 TTL——它是累计预算的分子，过期即等于预算重置 */
    private static final String USED_KEY_PREFIX = "im:ai:used:";
    private static final int ERROR_MSG_MAX = 255;

    private final ImProperties imProperties;
    private final AiUsageMapper usageMapper;
    private final AiGuardMapper guardMapper;
    private final RedisUtil redisUtil;

    /**
     * 付费调用前的闸门：封禁与预算二合一检查，放行即返回发起人 ID 供 {@link #recordUsage} 记账。
     *
     * <p>必须在<b>带请求上下文</b>的线程上调（读 satoken 登录态与角色）；
     * SSE 的虚拟线程里读不到，所以 {@code InterviewService.chatAsync} 在进线程池前完成本检查，
     * 把 userId 一路带进推流任务。
     */
    public long checkAllowed(String scene) {
        long userId = SecurityUtil.getUserId();
        if (!imProperties.getAiGuard().isEnabled()) {
            return userId;
        }
        if (SecurityUtil.isAdmin()) {
            return userId;
        }
        try {
            AiGuard guard = guardMapper.selectById(userId);
            if (guard != null && Integer.valueOf(1).equals(guard.getBanned())) {
                throw new BusinessException(ResultCode.AI_USAGE_BANNED);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[AI 护栏] 查询封禁状态失败，按未封禁放行 userId={}: {}", userId, e.getMessage());
        }
        long budgetMs = imProperties.getAiGuard().getTrialSeconds() * 1000L;
        if (budgetMs <= 0) {
            // 预算调成 0 就是对所有非 admin 一刀切停用，比「关掉护栏」安全，见配置项注释
            throw new BusinessException(ResultCode.AI_QUOTA_EXCEEDED);
        }
        if (usedMillis(userId) >= budgetMs) {
            throw new BusinessException(ResultCode.AI_QUOTA_EXCEEDED);
        }
        return userId;
    }

    /**
     * 记一笔调用流水：成功扣预算，失败只留痕。本方法不抛异常（护栏故障不拦业务主路）。
     */
    public void recordUsage(long userId, String scene, long durationMs, boolean success, String errorMsg) {
        long elapsed = Math.max(0L, durationMs);
        try {
            AiUsage row = AiUsage.builder()
                    .userId(userId)
                    .scene(scene)
                    .durationMs(elapsed)
                    .success(success ? 1 : 0)
                    .errorMsg(cut(errorMsg))
                    .build();
            usageMapper.insert(row);
            if (success) {
                redisUtil.increment(USED_KEY_PREFIX + userId, elapsed);
            }
        } catch (Exception e) {
            log.warn("[AI 护栏] 用量落库失败 userId={} scene={} elapsed={}: {}", userId, scene, elapsed, e.getMessage());
        }
    }

    /** 该账号此刻已花的预算毫秒数（Redis 优先，miss 时按流水表重建） */
    public long usedMillis(long userId) {
        String key = USED_KEY_PREFIX + userId;
        String cached = redisUtil.get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException ignored) {
                // 脏值当 miss 处理，下面用库里的权威数字覆盖回去
            }
        }
        long sum = 0L;
        try {
            List<Map<String, Object>> rows = usageMapper.selectMaps(Wrappers.<AiUsage>query()
                    .select("COALESCE(SUM(duration_ms), 0) AS used")
                    .eq("user_id", userId)
                    .eq("success", 1));
            if (!rows.isEmpty() && rows.get(0).get("used") instanceof Number number) {
                sum = number.longValue();
            }
        } catch (Exception e) {
            log.warn("[AI 护栏] 重算已用预算失败 userId={}，按 0 放行: {}", userId, e.getMessage());
            return 0L;
        }
        redisUtil.raiseToAtLeast(key, sum);
        return sum;
    }

    /**
     * 配额概况，挂进 {@code /api/ai/interview/status} 让前端能显示「体验剩余 xx 秒」；
     * 也供后台聚合页复用同一套「此刻还剩多少」的算法。
     */
    public Map<String, Object> quotaView(long userId) {
        boolean enabled = imProperties.getAiGuard().isEnabled();
        long budgetMs = imProperties.getAiGuard().getTrialSeconds() * 1000L;
        boolean unlimited = !enabled || SecurityUtil.isAdmin();
        long usedMs = usedMillis(userId);
        return Map.of(
                "enabled", enabled,
                "unlimited", unlimited,
                "budgetMs", budgetMs,
                "usedMs", usedMs,
                "remainingMs", unlimited ? -1L : Math.max(0L, budgetMs - usedMs));
    }

    private static String cut(String value) {
        if (TextUtil.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > ERROR_MSG_MAX ? trimmed.substring(0, ERROR_MSG_MAX) : trimmed;
    }
}
