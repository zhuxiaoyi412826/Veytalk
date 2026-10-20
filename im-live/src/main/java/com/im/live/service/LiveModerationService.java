package com.im.live.service;

import com.im.common.api.ResultCode;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.live.config.LiveProperties;
import com.im.live.dto.po.LiveMutePO;
import com.im.live.dto.vo.LiveMuteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弹幕房管：主播维度的禁言名单与自定义屏蔽词。
 *
 * <h2>为什么挂主播而不是挂房间</h2>
 *
 * <p>{@code im_live_room} 一行 = <b>一场</b>直播，每次开播都是新 roomId。治理的语义显然是跨场次的
 * （「这个人来我直播间捣过乱，下次也不想让他发」），挂房间等于每开一场就把黑名单清一次。
 * 主播 id 是稳定的，于是两份数据都按 {@code anchorId} 存。
 *
 * <h2>为什么只存 Redis、不建表</h2>
 *
 * <p>这一版要的是零 DDL：不建表就不卡联调，也不给 {@code im_live_room} 加一列它本来不该管的治理数据。
 * 代价写明白在这里——<b>Redis 是唯一的存储</b>，键按 {@code im.live.moderation-retention-days}
 * （默认 365 天）滑动续期，一次 Redis 数据丢失或主播一年后不再开播，名单与词表都会消失。
 * 临时禁言丢了无所谓，「永久禁言」若要真正可靠必须落表，那属于下一期的房管持久化。
 *
 * <h2>判定链的位置</h2>
 *
 * <p>本服务只做判定与存取，<b>调用点在 {@code LiveDanmakuHandler.onDanmaku}</b>：长度校验与限流之后、
 * 广播之前。禁言判定每次都读 Redis（要实时生效），屏蔽词读走一层短 TTL 本地缓存（低频变更、高频读）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveModerationService {

    /** 永久禁言的到期标记：{@code expireAt <= 0} 一律视为永久 */
    private static final long PERMANENT = 0L;

    /**
     * 单个主播名单的人数上限。Redis Hash 没有原生上限，靠这里卡：
     * 否则一个脚本能把名单灌成几十万 field，HGETALL 直接把面板和 Redis 一起拖死。
     */
    private static final int MAX_MUTE_ENTRIES = 500;

    /** 拒发文案：不带具体命中了哪个词，免得观众用二分法把主播的词表试出来 */
    private static final String WORD_BLOCKED_HINT = "弹幕包含主播设置的屏蔽词";

    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final LiveProperties properties;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final UserQuerySpi userQuerySpi;
    private final LiveDanmakuService danmakuService;

    /** anchorId → 屏蔽词快照缓存。写词表时本机立即失效，其他实例最多滞后 {@code danmakuWordsCacheMillis} */
    private final Map<Long, WordSnapshot> wordCache = new ConcurrentHashMap<>();

    /** 一条弹幕的治理结论：{@link #rejectReason} 非空即拒发，否则用 {@link #content} 作为最终文本 */
    public record Decision(String content, String rejectReason) {
        public boolean blocked() {
            return rejectReason != null;
        }
    }

    /* ==================== 判定（弹幕热路径） ==================== */

    /**
     * 弹幕治理判定：禁言 → 主播自定义屏蔽词 → 全局敏感词遮罩。
     *
     * @param anchor 发言者本人是否为主播。主播跳过前两项（名单与词表都是他自己在管，禁自己毫无意义），
     *               但全局敏感词仍然遮罩——房管管的是观众，公共底线对主播同样成立。
     */
    public Decision inspect(Long anchorId, Long userId, String content, boolean anchor) {
        if (anchorId == null) {
            return new Decision(mask(content), null);
        }
        if (!anchor) {
            if (isMuted(anchorId, userId)) {
                return new Decision(null, ResultCode.LIVE_USER_MUTED.getMessage());
            }
            if (hitWord(anchorId, content)) {
                return new Decision(null, WORD_BLOCKED_HINT);
            }
        }
        return new Decision(mask(content), null);
    }

    /** 该用户是否被这个主播禁言（含永久）。到期即视为未禁言，并顺手把这条僵尸记录删掉 */
    public boolean isMuted(Long anchorId, Long userId) {
        if (anchorId == null || userId == null) {
            return false;
        }
        LiveMutePO po = readMute(anchorId, userId);
        if (po == null) {
            return false;
        }
        if (isLive(po)) {
            return true;
        }
        redisUtil.hDelete(RedisKeys.liveMute(anchorId), String.valueOf(userId));
        return false;
    }

    /* ==================== 禁言名单 ==================== */

    /**
     * 禁言某人。
     *
     * @param minutes 正数为分钟数，{@code 0} 或负数表示永久
     * @param roomId  可空；带上时给那个人的在线连接即时推一帧提示
     */
    public void mute(Long anchorId, Long userId, Integer minutes, Long roomId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "用户 ID 不能为空");
        BusinessException.throwIf(userId.equals(anchorId), ResultCode.LIVE_MUTE_SELF);
        String key = RedisKeys.liveMute(anchorId);
        String field = String.valueOf(userId);
        // 已满时只允许「给已在名单里的人改时长」，不允许再新加一个人
        boolean already = redisUtil.hHasKey(key, field);
        BusinessException.throwIf(!already && redisUtil.hLen(key) >= MAX_MUTE_ENTRIES,
                ResultCode.BUSINESS_ERROR, "禁言名单已达 " + MAX_MUTE_ENTRIES + " 人，请先清理");

        int minutesValue = minutes == null ? 5 : minutes;
        long expireAt = minutesValue <= 0 ? PERMANENT
                : System.currentTimeMillis() + minutesValue * 60_000L;
        UserBriefDTO target = userQuerySpi.getById(userId);
        LiveMutePO po = LiveMutePO.builder()
                .expireAt(expireAt)
                .mutedAt(System.currentTimeMillis())
                .nickname(target == null ? "观众" : displayName(target))
                .avatar(target == null || target.getAvatar() == null ? "" : target.getAvatar())
                .build();
        String json = jsonUtil.toJson(po);
        BusinessException.throwIf(json == null, ResultCode.SYSTEM_ERROR);
        redisUtil.hSet(key, field, json);
        touch(key);

        if (roomId != null) {
            danmakuService.sendToUser(roomId, userId, danmakuService.frame(
                    LiveDanmakuService.TYPE_SYSTEM, "content",
                    minutesValue <= 0 ? "主播已将你禁言" : "主播已将你禁言 " + minutesValue + " 分钟"));
        }
        log.info("弹幕禁言: anchorId={}, userId={}, minutes={}, roomId={}", anchorId, userId, minutesValue, roomId);
    }

    /** 解除禁言（幂等：不在名单里也算成功） */
    public void unmute(Long anchorId, Long userId, Long roomId) {
        if (anchorId == null || userId == null) {
            return;
        }
        redisUtil.hDelete(RedisKeys.liveMute(anchorId), String.valueOf(userId));
        if (roomId != null) {
            danmakuService.sendToUser(roomId, userId, danmakuService.frame(
                    LiveDanmakuService.TYPE_SYSTEM, "content", "主播已解除对你的禁言"));
        }
    }

    /** 名单（按禁言时刻倒序），已到期但还没来得及懒删的记录不会出现在这里 */
    public List<LiveMuteVO> listMutes(Long anchorId) {
        List<LiveMuteVO> list = new ArrayList<>();
        if (anchorId == null) {
            return list;
        }
        String key = RedisKeys.liveMute(anchorId);
        Set<String> expired = new LinkedHashSet<>();
        for (Map.Entry<Object, Object> entry : redisUtil.hGetAll(key).entrySet()) {
            String field = String.valueOf(entry.getKey());
            LiveMutePO po = parseMute(entry.getValue());
            if (po == null) {
                expired.add(field);
                continue;
            }
            if (!isLive(po)) {
                expired.add(field);
                continue;
            }
            list.add(LiveMuteVO.builder()
                    .userId(parseLong(field))
                    .nickname(po.getNickname())
                    .avatar(po.getAvatar())
                    .expireAt(po.getExpireAt())
                    .permanent(po.getExpireAt() == null || po.getExpireAt() <= 0)
                    .mutedAt(po.getMutedAt())
                    .build());
        }
        // 一次读到过期的记录集中删，别的面板刷新时又扫一遍
        if (!expired.isEmpty()) {
            redisUtil.hDelete(key, expired.toArray());
        }
        list.sort((a, b) -> Long.compare(nullToZero(b.getMutedAt()), nullToZero(a.getMutedAt())));
        return list;
    }

    /* ==================== 屏蔽词 ==================== */

    public List<String> wordsOf(Long anchorId) {
        if (anchorId == null) {
            return List.of();
        }
        return new ArrayList<>(wordsOfSet(anchorId));
    }

    /**
     * 整表替换。清洗规则：折叠（全角转半角 + 小写 + 去内部空白）后去重、丢空串；
     * 超长或数量超限直接报错而不是静默截断——静默截断会让主播以为自己已经加上了那个词。
     */
    public void replaceWords(Long anchorId, Collection<String> rawWords) {
        BusinessException.throwIf(anchorId == null, ResultCode.SYSTEM_ERROR);
        Set<String> cleaned = new LinkedHashSet<>();
        int maxLen = Math.max(1, properties.getDanmakuWordMaxLength());
        for (String raw : rawWords == null ? List.<String>of() : rawWords) {
            String word = fold(raw);
            if (word.isEmpty()) {
                continue;
            }
            BusinessException.throwIf(word.length() > maxLen, ResultCode.BAD_REQUEST,
                    "屏蔽词最多 " + maxLen + " 字：" + word);
            cleaned.add(word);
        }
        int maxCount = Math.max(1, properties.getDanmakuWordsMaxCount());
        BusinessException.throwIf(cleaned.size() > maxCount, ResultCode.BAD_REQUEST,
                "屏蔽词最多 " + maxCount + " 个");

        String key = RedisKeys.liveWords(anchorId);
        // 先删后加：Redis 没有「整体替换 Set」的原子命令。中间那一瞬并发读到的词表可能为空，
        // 代价是极小概率漏拦一条弹幕——比反过来（残留旧词）更容易被接受
        redisUtil.delete(key);
        if (!cleaned.isEmpty()) {
            redisUtil.sAdd(key, cleaned.toArray(new String[0]));
            touch(key);
        }
        wordCache.remove(anchorId);
        log.info("弹幕屏蔽词更新: anchorId={}, 生效 {} 个", anchorId, cleaned.size());
    }

    /* ==================== 内部 ==================== */

    private boolean hitWord(Long anchorId, String content) {
        Set<String> words = wordsOfSet(anchorId);
        if (words.isEmpty()) {
            return false;
        }
        String folded = fold(content);
        for (String word : words) {
            if (folded.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 读词表：命中未过期的本机快照就不碰 Redis */
    private Set<String> wordsOfSet(Long anchorId) {
        long ttl = Math.max(0L, properties.getDanmakuWordsCacheMillis());
        WordSnapshot cached = wordCache.get(anchorId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < ttl) {
            return cached.words;
        }
        Set<String> words = redisUtil.sMembers(RedisKeys.liveWords(anchorId));
        wordCache.put(anchorId, new WordSnapshot(words == null ? Set.of() : words, now));
        return words == null ? Set.of() : words;
    }

    private LiveMutePO readMute(Long anchorId, Long userId) {
        Object raw = redisUtil.hGet(RedisKeys.liveMute(anchorId), String.valueOf(userId));
        if (raw == null) {
            return null;
        }
        LiveMutePO po = parseMute(raw);
        if (po == null) {
            // 读不懂的值一律按「无记录」处理并删掉：宁可放行一条弹幕，也不能让一个坏值把某人永久禁言
            redisUtil.hDelete(RedisKeys.liveMute(anchorId), String.valueOf(userId));
        }
        return po;
    }

    private LiveMutePO parseMute(Object raw) {
        if (raw == null) {
            return null;
        }
        return jsonUtil.fromJsonQuietly(String.valueOf(raw), LiveMutePO.class);
    }

    private boolean isLive(LiveMutePO po) {
        Long expireAt = po.getExpireAt();
        return expireAt != null && (expireAt <= 0 || expireAt > System.currentTimeMillis());
    }

    /** 全局敏感词遮罩：弹幕原本不过滤，这里补上；关遮罩只影响弹幕，不动 IM 消息那条链 */
    private String mask(String content) {
        if (!properties.isDanmakuSensitiveMask()) {
            return content;
        }
        return sensitiveWordFilter.mask(content);
    }

    /** 滑动续期：只要主播还在管理房管，键就继续活着 */
    private void touch(String key) {
        int days = Math.max(1, properties.getModerationRetentionDays());
        redisUtil.expire(key, Duration.ofDays(days));
    }

    /**
     * 折叠：全角 ASCII 转半角、统一小写、去掉所有空白。
     *
     * <p>去空白是为了拦住「加 微 信」这类隔字写法。与 {@code SensitiveWordFilter} 的
     * 干扰符跳过相比这里只做最粗的归一：房间词表通常几个到几十个词、都是主播随手打的，
     * 上字典树不划算，误伤也更容易被主播自己发现并删掉。
     */
    private static String fold(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private String displayName(UserBriefDTO user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername() == null ? "观众" : user.getUsername();
    }

    private Long parseLong(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /** 词表快照：整体替换、不做增量维护，所以只要引用不变就可以安全共享 */
    private static final class WordSnapshot {
        private final Set<String> words;
        private final long loadedAt;

        private WordSnapshot(Set<String> words, long loadedAt) {
            this.words = words;
            this.loadedAt = loadedAt;
        }
    }
}
