package com.im.message.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import com.im.message.dto.req.ForwardMessageRequest;
import com.im.message.dto.req.MessageIdsRequest;
import com.im.message.dto.req.MessageSearchQuery;
import com.im.message.dto.req.ReadReportRequest;
import com.im.message.dto.req.SendMessageRequest;
import com.im.message.dto.vo.MessageVO;
import com.im.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消息接口，全部需要登录。
 *
 * <p>这里的每个写操作在 WebSocket 通道上都有一份等价入口（{@code chat} / {@code delivered} /
 * {@code read} / {@code recall}），两条通道最终都落到同一个 Service，行为完全一致。
 * REST 通道的存在意义是兜底：长连接不可用、客户端刚打开页面还没建连时，消息照样能发能查。
 *
 * <p>路径上没有 {@code GET /{id}}，因此 {@code history} / {@code offline} / {@code search}
 * 三个字面量段不会被误当成消息 ID 解析；清除离线标记也刻意做成两段的 {@code /offline/clear}，
 * 避开与 {@code DELETE /{id}} 同段同方法的歧义。
 */
@Tag(name = "04-消息", description = "消息收发、历史分页、撤回删除、送达已读上报、离线消息与内容检索")
@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
@SaCheckLogin
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "发送消息",
            description = "clientMsgId 幂等，重复提交返回首次结果；会话定位优先级 conversationId > toUserId > toGroupId；"
                    + "附件类消息的 content 传文件 ID，服务端会按文件记录回填元数据")
    @PostMapping("/send")
    @SaCheckPermission(ImConstants.PERM_MESSAGE_SEND)
    public Result<MessageVO> send(@RequestBody @Valid SendMessageRequest request) {
        return Result.ok(messageService.sendForView(SecurityUtil.getUserId(), request));
    }

    @Operation(summary = "历史消息分页",
            description = "基于 seq 的游标分页，按时间正序返回，已排除当前用户单端删除的消息；"
                    + "首次进入会话不传 beforeSeq 取最新一页，向上滚动时用本页第一条的 seq 作为游标")
    @GetMapping("/history")
    public Result<List<MessageVO>> history(
            @Parameter(description = "会话 ID") @RequestParam("conversationId") Long conversationId,
            @Parameter(description = "游标：只返回 seq 小于该值的消息，为空表示从最新一条开始")
            @RequestParam(value = "beforeSeq", required = false) Long beforeSeq,
            @Parameter(description = "本页条数，默认 20，最大 100")
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(messageService.history(userId, conversationId, beforeSeq, size == null ? 0 : size));
    }

    @Operation(summary = "离线消息",
            description = "返回上次确认位点之后的全部消息，按会话与 seq 升序，单次最多 500 条；"
                    + "拉取后自动推进位点，但不清未读数——未读要等用户真正打开会话才消失")
    @GetMapping("/offline")
    public Result<List<MessageVO>> offline() {
        return Result.ok(messageService.offlineView(SecurityUtil.getUserId()));
    }

    @Operation(summary = "清除离线标记",
            description = "把用户在全部会话上的确认位点直接推到当前最大 seq，丢弃尚未拉取的离线消息；"
                    + "仅在客户端明确不需要补收历史时调用，正常重连流程用 GET /offline 即可")
    @PutMapping("/offline/clear")
    public Result<Void> clearOffline() {
        messageService.clearOffline(SecurityUtil.getUserId());
        return Result.ok(null, "已清除离线标记");
    }

    @Operation(summary = "消息内容检索",
            description = "必须指定会话：消息表是全系统增长最快的表，不带会话范围的模糊查询会退化成全表扫描")
    @GetMapping("/search")
    public Result<PageResult<MessageVO>> search(@Valid MessageSearchQuery query) {
        return Result.ok(messageService.search(SecurityUtil.getUserId(), query));
    }

    @Operation(summary = "撤回消息",
            description = "默认 2 分钟内可撤回；本人可撤回自己的消息，群主与管理员可撤回群内任意消息；"
                    + "撤回后原文对所有成员失效，会话摘要变为「xx 撤回了一条消息」")
    @PutMapping("/{id}/recall")
    public Result<Void> recall(@Parameter(description = "消息 ID") @PathVariable("id") Long id) {
        messageService.recall(id, SecurityUtil.getUserId());
        return Result.ok(null, "消息已撤回");
    }

    @Operation(summary = "删除消息",
            description = "只对当前用户不可见，其他成员照常看到，与撤回是两件事；删除没有时间限制")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "消息 ID") @PathVariable("id") Long id) {
        messageService.deleteForUser(SecurityUtil.getUserId(), id);
        return Result.ok(null, "消息已删除");
    }

    @Operation(summary = "已读上报",
            description = "写入已读回执、清零会话未读，并把「已读」推送给各条消息的发送方；"
                    + "不传 maxSeq 表示会话内全部已读，单次最多处理 500 条")
    @PutMapping("/read")
    public Result<Void> markRead(@RequestBody @Valid ReadReportRequest request) {
        messageService.markRead(SecurityUtil.getUserId(), request.getConversationId(), request.getMaxSeq());
        return Result.ok(null, "已标记为已读");
    }

    @Operation(summary = "送达上报",
            description = "客户端收到消息后回报送达，发送方气泡上的单勾靠它点亮；单次最多 200 条，自己发的消息会被自动忽略")
    @PostMapping("/delivered")
    public Result<Void> markDelivered(@RequestBody @Valid MessageIdsRequest request) {
        messageService.markDelivered(SecurityUtil.getUserId(), request.getMessageIds());
        return Result.ok(null, "已上报送达");
    }

    @Operation(summary = "转发消息",
            description = "把一条已存在的消息复制到目标会话；附件复用原文件不重新上传，"
                    + "转发者必须是原会话成员；目标会话定位优先级 conversationId > toUserId > toGroupId")
    @PostMapping("/forward")
    @SaCheckPermission(ImConstants.PERM_MESSAGE_SEND)
    public Result<MessageVO> forward(@RequestBody @Valid ForwardMessageRequest request) {
        return Result.ok(messageService.forward(SecurityUtil.getUserId(), request));
    }
}
