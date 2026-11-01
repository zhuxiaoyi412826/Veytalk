package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminAiOverviewDTO;
import com.im.common.domain.AdminAiUsageDTO;
import com.im.common.domain.AdminAiUsageQueryDTO;
import com.im.common.domain.AdminAiUserDTO;
import com.im.common.domain.AdminAiUserQueryDTO;

/**
 * AI 用量管控契约（四期第六批），由 im-ai 实现（{@code AiGuardAdminSpiImpl}）。
 *
 * <p>星型架构老问题：im-admin 不能 import {@code AiUsageMapper}，而「谁烧了多少模型、
 * 封禁谁的 AI 功能」这些事只有 {@code im_ai_usage} / {@code im_ai_guard} 两张表的
 * 主人 im-ai 能做，于是契约反转给它。消费侧照例 {@code ObjectProvider}——
 * 没装 im-ai 时后台显示「模块未部署」，而不是 500。
 *
 * <p>与 {@code AdminInterviewSpi}（纯只读）不同，本契约带两个写方法 {@link #ban}/{@link #unban}：
 * 封禁的就是「调用付费模型」这一项能力本身，<b>不动账号登录</b>——
 * 停一个人的 AI 与停一个人的号是两个量级的处置，后者去 {@code /api/admin/user} 走那条路。
 * 封禁即时生效（护栏每次调用现查库），解封同样即时。
 */
public interface AdminAiGuardSpi {

    /** 顶部概览：护栏运行态 + 全历史/今日数字 + 烧钱榜前几名 */
    AdminAiOverviewDTO overview();

    /** 按人聚合翻页，累计耗时倒序；bannedOnly 为真时只回被封禁的人 */
    PageResult<AdminAiUserDTO> pageUsers(AdminAiUserQueryDTO query);

    /** 调用明细翻页，时间倒序 */
    PageResult<AdminAiUsageDTO> pageUsage(AdminAiUsageQueryDTO query);

    /**
     * 封禁某人的 AI 功能（幂等：已封禁再封只更新原因）。
     *
     * @param operatorId 后台操作人 ID，落 {@code update_by} 问责
     * @param reason     封禁原因，展示给用户与操作日志用；空白由实现方兜底成默认文案
     */
    void ban(Long userId, String reason, Long operatorId);

    /** 解除封禁（幂等：没封过直接返回） */
    void unban(Long userId, Long operatorId);
}
