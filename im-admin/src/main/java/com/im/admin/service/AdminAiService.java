package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminAiOverviewDTO;
import com.im.common.domain.AdminAiUsageDTO;
import com.im.common.domain.AdminAiUsageQueryDTO;
import com.im.common.domain.AdminAiUserDTO;
import com.im.common.domain.AdminAiUserQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminAiGuardSpi;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台 AI 用量管控：概览、按人聚合、调用明细、封禁与解封。
 *
 * <p>薄封装，本类不读 {@code im_ai_usage} / {@code im_ai_guard} 任何一张表，
 * 一切经 {@link AdminAiGuardSpi} 交给 im-ai——星型架构老规矩，im-admin 的 pom
 * 里没有 im-ai，也不该有。
 *
 * <p>与面试记录那一屏（纯只读）不同，这里带两个写动作：封禁停的只是「调用付费模型」
 * 这一项能力，<b>不动账号登录</b>——停一个人的 AI 与停一个人的号是两个量级的处置，
 * 后者去 {@code /api/admin/user} 走那条有留痕的路。封禁即时生效：护栏每次调用现查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAiService {

    private final ObjectProvider<AdminAiGuardSpi> aiGuardSpiProvider;

    public AdminAiOverviewDTO overview() {
        return requireSpi().overview();
    }

    public PageResult<AdminAiUserDTO> pageUsers(AdminAiUserQueryDTO query) {
        return requireSpi().pageUsers(query);
    }

    public PageResult<AdminAiUsageDTO> pageUsage(AdminAiUsageQueryDTO query) {
        return requireSpi().pageUsage(query);
    }

    /** 封禁某人的 AI 功能（幂等：已封禁再封只更新原因） */
    public void ban(Long userId, String reason) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "用户 ID 必填");
        String text = TextUtil.isBlank(reason) ? null : reason.trim();
        if (text != null && text.length() > 200) {
            text = text.substring(0, 200);
        }
        requireSpi().ban(userId, text, SecurityUtil.getUserId());
        log.info("[AI 护栏] 后台封禁 userId={}, 操作人={}", userId, SecurityUtil.getUserId());
    }

    /** 解除封禁（幂等：没封过直接成功返回） */
    public void unban(Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "用户 ID 必填");
        requireSpi().unban(userId, SecurityUtil.getUserId());
        log.info("[AI 护栏] 后台解封 userId={}, 操作人={}", userId, SecurityUtil.getUserId());
    }

    private AdminAiGuardSpi requireSpi() {
        AdminAiGuardSpi spi = aiGuardSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "AI 模块未装配，后台无法查看用量管控");
        }
        return spi;
    }
}
