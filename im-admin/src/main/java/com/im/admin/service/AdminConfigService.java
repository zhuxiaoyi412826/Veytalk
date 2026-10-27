package com.im.admin.service;

import com.im.admin.config.AdminConfigKey;
import com.im.admin.dto.vo.ConfigItemVO;
import com.im.admin.dto.vo.SensitiveFilterVO;
import com.im.common.sensitive.SensitiveWordFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 后台系统配置页的读出口（四期）。
 *
 * <p>四期第一批时这个类只有「改 {@code SensitiveWordFilter} 那个内存标志」一件事，
 * 账本也只有一份，重启回到配置文件的值。现在那一件事也走 {@link RuntimeConfigService}：
 * 落库 + 改运行态，重启后由 {@code RuntimeConfigSyncTask} 把库里那行盖回来。
 * 保留本类而不是让前端直接打到 {@link RuntimeConfigService}，是因为敏感词开关这一个动作
 * 有它自己的一段历史（原先它挂在用户侧 {@code /api/message/sensitive-filter}，任何登录用户都能关），
 * 前端那一对端点已经写了两个版本，路径与返回形状都不能动。
 *
 * <p>切换必须记操作人。这个动作的影响面是<b>全服所有文本与文件名</b>，
 * 出问题时「谁在什么时候关的」是唯一能问的话，而 {@code SensitiveWordFilter}
 * 自己那条日志只有开关值没有操作人（它在 im-common，不知道 HTTP 上下文）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminConfigService {

    private final SensitiveWordFilter sensitiveWordFilter;
    private final RuntimeConfigService runtimeConfigService;

    /* ==================== 敏感词开关（沿用四期第一批的端点形状） ==================== */

    /** 当前运行态：开关 + 词库装载情况 + 生效词条数 + 是否已落库覆盖 */
    public SensitiveFilterVO sensitiveFilter() {
        return toVO();
    }

    /**
     * 热切换过滤开关，并把这一次改动<b>落库</b>。
     *
     * @param enabled    true 开启遮罩，false 关闭
     * @param operatorId 操作人（后台账号 ID），只用于审计日志与 {@code update_by}
     * @return 切换后的运行态，让前端以服务端为准回填，不相信自己点下去的那个值
     */
    public SensitiveFilterVO setSensitiveFilter(Boolean enabled, Long operatorId) {
        boolean target = Boolean.TRUE.equals(enabled);
        boolean previous = sensitiveWordFilter.isEnabled();
        runtimeConfigService.update(AdminConfigKey.SENSITIVE_FILTER_ENABLED.key(), String.valueOf(target), operatorId);
        log.info("[后台配置] 敏感词过滤开关由 {} 切换为 {}（操作人 {}，全服立即生效，已落库、重启后仍按这一次）",
                previous ? "开启" : "关闭", target ? "开启" : "关闭", operatorId);
        return toVO();
    }

    /* ==================== 系统配置目录（四期第四批） ==================== */

    /** 全部可改项：目录元数据 + 当前运行态 + 是否已被后台覆盖 */
    public List<ConfigItemVO> configItems() {
        return runtimeConfigService.listAll();
    }

    /**
     * 改一项配置。
     *
     * <p>白名单、取值范围、落库与运行态写入的先后都在 {@link RuntimeConfigService} 里，
     * 本类只是那一层的服务端出口，不再自己判断一次——两处判断迟早漂移成「一处放行一处拒绝」。
     */
    public ConfigItemVO updateConfigItem(String key, String value, Long operatorId) {
        return runtimeConfigService.update(key, value, operatorId);
    }

    /** 恢复默认：删掉库内那一行，运行态回到启动默认值 */
    public ConfigItemVO resetConfigItem(String key, Long operatorId) {
        return runtimeConfigService.reset(key, operatorId);
    }

    private SensitiveFilterVO toVO() {
        SensitiveFilterVO vo = new SensitiveFilterVO();
        vo.setEnabled(sensitiveWordFilter.isEnabled());
        vo.setReady(sensitiveWordFilter.isReady());
        vo.setWordCount(sensitiveWordFilter.getWordCount());
        vo.setOverridden(runtimeConfigService.isOverridden(AdminConfigKey.SENSITIVE_FILTER_ENABLED));
        String startupDefault = runtimeConfigService.startupDefault(AdminConfigKey.SENSITIVE_FILTER_ENABLED);
        vo.setStartupDefault("true".equals(startupDefault));
        return vo;
    }
}
