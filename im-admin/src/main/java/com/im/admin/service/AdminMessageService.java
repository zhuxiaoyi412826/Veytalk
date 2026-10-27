package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminMessageDTO;
import com.im.common.domain.AdminMessageOverviewDTO;
import com.im.common.domain.AdminMessageQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminMessageSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台消息管理：消息检索、单条全文档案、顶部概览、强制撤回。
 *
 * <p>薄封装，本类不读 {@code im_message}，一切经 {@link AdminMessageSpi} 交给 im-message；
 * 强制撤回在 im-message 内部再收敛到 {@code MessageServiceImpl#recallInternal} 的
 * {@code force} 分支——与举报处置撤回那条消息走的是同一段代码。
 *
 * <p><b>这是整个后台最敏感的一屏</b>：{@code keyword} 检索等于读任意两个人的聊天正文，
 * 所以它单挂 {@code system:message:manage}，与 {@code system:log} 同级对待，
 * 给了 {@code system:group:manage} 或 {@code system:report:handle} 都不顺带得到它。
 * 服务端另外三道硬闸由实现方把：有 keyword 必须带范围、单页 50、正文截到 2000 字
 * （读全文要点开档案，那个动作会在后端留一条日志）。
 *
 * <p>这一屏与用户侧的 {@code message:recall:any} 是两套点：后者是聊天端的功能权限，
 * 能在撤回时限内撤别人的消息（多见于群管理员）；这里的 {@code forceRecall} 无视时限。
 * 但「转账与红包不能撤」「系统通知不能撤」在后台同样拒绝——那两条拦的不是权限，
 * 而是「撤回会把已经生效的资金凭证抹掉」这个客观事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMessageService {

    private final ObjectProvider<AdminMessageSpi> messageSpiProvider;

    public PageResult<AdminMessageDTO> page(AdminMessageQueryDTO query) {
        return requireSpi().pageMessages(query);
    }

    /**
     * 单条消息全文档案：正文不截断，另带引用原消息的摘要。
     *
     * <p>这是唯一会把完整 {@code content} 交出去的路径，实现在 im-message 侧打一行含
     * messageId 的后端日志——「读全文」应当是一个独立的、留痕的动作。
     */
    public AdminMessageDTO detail(Long messageId) {
        BusinessException.throwIf(messageId == null, ResultCode.BAD_REQUEST, "消息 ID 必填");
        AdminMessageDTO message = requireSpi().detail(messageId);
        if (message == null) {
            throw new BusinessException(ResultCode.MESSAGE_NOT_FOUND);
        }
        return message;
    }

    public AdminMessageOverviewDTO overview() {
        return requireSpi().overview();
    }

    /**
     * 强制撤回，返回撤回后的档案。
     *
     * <p>幂等：目标早就被撤过时实现方静默返回当前档案（与用户侧同口径），
     * 所以「两次点击」与「两个人同时处置同一条」都不会出现「第二次失败所以不知道撤没撤」。
     * 撤回后在场的人会在会话里看到「平台管理员撤回了一条消息」——让在场的人知道是谁撤的，
     * 比让后台的处置动作不留痕迹更重要。
     */
    public AdminMessageDTO forceRecall(Long messageId, Long adminId) {
        AdminMessageSpi spi = requireSpi();
        AdminMessageDTO message = spi.forceRecall(messageId, adminId);
        log.info("[后台消息管理] 强制撤回完成: messageId={}, admin={}", messageId, adminId);
        return message;
    }

    private AdminMessageSpi requireSpi() {
        AdminMessageSpi spi = messageSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "消息模块未装配，后台无法管理消息");
        }
        return spi;
    }
}
