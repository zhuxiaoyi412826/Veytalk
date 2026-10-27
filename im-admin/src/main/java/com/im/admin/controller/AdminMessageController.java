package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminMessageService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminMessageDTO;
import com.im.common.domain.AdminMessageOverviewDTO;
import com.im.common.domain.AdminMessageQueryDTO;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的消息管理（四期第五批）——整个后台最敏感的一屏。
 *
 * <p>{@code keyword} 检索等于<b>读任意两个人的聊天正文</b>，所以权限单设
 * {@code system:message:manage}，与 {@code system:log} 同级对待：已经给了某人
 * {@code system:group:manage} 或 {@code system:report:handle}，也仍然要单独授这一点
 * 才看得到这一屏。这不是形式——举报处置那一屏本来就能看见被举报消息的上下文，
 * 如果这里不设独立点，「按关键词全库搜聊天记录」就会顺着同一个入口一起被给出去。
 *
 * <p>服务端的三道硬闸与权限无关，只与代价和越权面有关：
 * <b>有 keyword 就必须带范围</b>（会话 / 发送者 / 时间窗三者其一，否则 1001 拒绝——
 * 界面提示会被绕过，curl 不会读注释）、单页 50、列表正文截到 2000 字。
 * 读全文是另一个端点（{@code /{messageId}}），那个动作会在后端留一条日志。
 *
 * <p>响应<b>不含 fileUrl</b>：那是受控访问地址。图片 / 文件 / 语音的 {@code content}
 * 存的是文件 ID 而不是可读文本，所以内容列实际看的是 {@code extra} 摘要（文件名、尺寸、时长）。
 * 想看附件本体要走文件那一屏的鉴权路径——管理员凭这一屏的权限<b>不该</b>拿到别人会话里的附件本体。
 *
 * <p>强制撤回与用户侧的 {@code message:recall:any} 是两套点：后者是聊天端的功能权限，
 * 能在撤回时限内撤别人的消息（多见于群管理员）；这里无视时限。但转账 / 红包与系统通知
 * 在后台同样被拒绝——那两条拦的不是权限，而是「撤回会把已经生效的资金凭证抹掉」这个事实。
 */
@Tag(name = "16-管理后台", description = "消息管理：检索、全文档案与强制撤回")
@RestController
@RequestMapping("/api/admin/message")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_MESSAGE_MANAGE)
public class AdminMessageController {

    private final AdminMessageService adminMessageService;

    @Operation(summary = "消息检索",
            description = "排序固定按主键倒序（雪花 ID 即时间序），不接受排序参数。"
                    + "<b>带 keyword 必须同时带 conversationId / fromUserId / 时间范围之一</b>，"
                    + "否则回 1001：那条 LIKE 是对全库最大的一张表做顺序扫描。默认含已撤回的行，"
                    + "recalledOnly 才筛。单聊行带 readCount，群聊为 null（群的已读要逐成员推算位点，不值那个钱）")
    @GetMapping("/page")
    public Result<PageResult<AdminMessageDTO>> page(AdminMessageQueryDTO query) {
        return Result.ok(adminMessageService.page(query));
    }

    @Operation(summary = "单条全文档案",
            description = "唯一交出完整正文的端点（列表里超过 2000 字的部分在这里给全），"
                    + "另带引用原消息的一句话摘要；读一次在后端留一条含 messageId 的日志。"
                    + "消息不存在回 5001；已被清理的引用行显示为「原消息已清理 #ID」")
    @GetMapping("/{messageId}")
    public Result<AdminMessageDTO> detail(@Parameter(description = "消息 ID") @PathVariable("messageId") Long messageId) {
        return Result.ok(adminMessageService.detail(messageId));
    }

    @Operation(summary = "概览数字",
            description = "今日 / 近 7 日消息量、今日「发出且已被撤回」的条数（其中后台强撤几条单独列出，"
                    + "那是<b>对后台自身的问责指标</b>）、保留天数与库里最早一条的时间——"
                    + "retention-days 默认 0 表示永不清理，那时关键词检索就是真·全表扫，"
                    + "expireDays=0 就是这个提醒。刻意不给全站总条数")
    @GetMapping("/overview")
    public Result<AdminMessageOverviewDTO> overview() {
        return Result.ok(adminMessageService.overview());
    }

    @Operation(summary = "强制撤回",
            description = "无视时限与发送者身份，但转账 / 红包（撤回等于抹掉收款凭证）与系统通知仍被拒绝；"
                    + "已撤回过的幂等返回当前档案。撤回后双方在会话里看到「平台管理员撤回了一条消息」，"
                    + "这条消息的 extra 里落下 recalledByAdmin 标记（<b>不下发给任何客户端</b>）")
    @PostMapping("/{messageId}/recall")
    public Result<AdminMessageDTO> recall(@Parameter(description = "消息 ID") @PathVariable("messageId") Long messageId) {
        return Result.ok(adminMessageService.forceRecall(messageId, SecurityUtil.getUserId()), "消息已强制撤回");
    }
}
