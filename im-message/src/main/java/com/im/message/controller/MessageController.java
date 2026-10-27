package com.im.message.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.PageQuery;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.message.dto.req.ForwardMessageRequest;
import com.im.message.dto.req.EditMessageRequest;
import com.im.message.dto.req.EssenceRequest;
import com.im.message.dto.req.ChainRequest;
import com.im.message.dto.req.FavoriteRequest;
import com.im.message.dto.req.MessageIdsRequest;
import com.im.message.dto.req.MessageSearchQuery;
import com.im.message.dto.req.ReactionRequest;
import com.im.message.dto.req.VoteRequest;
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
 * <p>路径上没有 {@code GET /{id}}，因此 {@code history} / {@code offline} / {@code search} /
 * {@code favorites} 这些字面量段不会被误当成消息 ID 解析；清除离线标记也刻意做成两段的 {@code /offline/clear}，
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
    @RateLimit(count = 120, dimension = RateLimit.Dimension.USER, key = "message.send")
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

    @Operation(summary = "导出完整聊天记录",
            description = "一次性返回当前用户在指定会话的全部消息（服务端权威数据，非本地缓存），按 seq 正序，已排除本人单端删除的消息；"
                    + "仅会话成员可导，单次上限 20000 条，供前端拼装为 JSON/CSV 下载与合规备份/迁移")
    @GetMapping("/export")
    @RateLimit(count = 20, dimension = RateLimit.Dimension.USER, key = "message.export")
    public Result<List<MessageVO>> exportConversation(
            @Parameter(description = "会话 ID") @RequestParam("conversationId") Long conversationId) {
        return Result.ok(messageService.exportConversation(SecurityUtil.getUserId(), conversationId));
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
            description = "conversationId 可选：指定时在该会话内检索；为空时跨当前用户参与的全部会话全局检索。"
                    + "两种情形都把 LIKE 的扫描范围收敛到会话集合内，不会退化成全表扫描")
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

    @Operation(summary = "编辑消息",
            description = "仅发送者本人、时限内（复用 im.message.recall-limit-seconds）且未撤回的文本消息可编辑；"
                    + "新正文重新过 XSS 清洗与敏感词遮蔽，就地改写并在 extra 打 edited 标记，新正文实时推给会话全员")
    @PutMapping("/{id}/edit")
    public Result<MessageVO> edit(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                  @RequestBody @Valid EditMessageRequest request) {
        return Result.ok(messageService.edit(SecurityUtil.getUserId(), id, request.getContent()));
    }

    @Operation(summary = "语音转文字",
            description = "对一条未撤回的语音消息调用外部 ASR 服务转写为文本（仅展示不落库，仅会话成员可用）；"
                    + "未接入 ASR 厂商时优雅降级为『语音转文字服务未配置或暂不可用』")
    @RateLimit(count = 60, dimension = RateLimit.Dimension.USER, key = "message.to-text")
    @PostMapping("/{id}/to-text")
    public Result<String> toText(@Parameter(description = "消息 ID") @PathVariable("id") Long id) {
        return Result.ok(messageService.toText(SecurityUtil.getUserId(), id));
    }

    @Operation(summary = "消息翻译",
            description = "对一条未撤回的文本消息调用外部翻译服务译为目标语言（仅展示不改写原消息，仅会话成员可用）；"
                    + "lang 为空时取配置默认目标语言；未接入翻译厂商时优雅降级为『消息翻译服务未配置或暂不可用』")
    @RateLimit(count = 60, dimension = RateLimit.Dimension.USER, key = "message.translate")
    @PostMapping("/{id}/translate")
    public Result<String> translate(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                    @Parameter(description = "目标语言代码，如 en-US/ja-JP，为空取默认")
                                    @RequestParam(value = "lang", required = false) String lang) {
        return Result.ok(messageService.translate(SecurityUtil.getUserId(), id, lang));
    }

    @Operation(summary = "表情回应",
            description = "对一条消息贴 / 取消一个表情（所有消息类型适用，已撤回不可）；一人对一条消息的同一表情只计一次，重复添加幂等")
    @RateLimit(count = 120, dimension = RateLimit.Dimension.USER, key = "message.reaction")
    @PostMapping("/{id}/reaction")
    public Result<MessageVO> react(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                   @RequestBody @Valid ReactionRequest request) {
        return Result.ok(messageService.react(SecurityUtil.getUserId(), id,
                request.getEmoji(), Boolean.TRUE.equals(request.getRemove())));
    }

    @Operation(summary = "投票",
            description = "对一条投票消息选择若干选项（整体替换旧票，空集合表示撤回本人投票）；close=true 由发起人截止")
    @RateLimit(count = 120, dimension = RateLimit.Dimension.USER, key = "message.vote")
    @PostMapping("/{id}/vote")
    public Result<MessageVO> vote(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                  @RequestBody @Valid VoteRequest request) {
        return Result.ok(messageService.vote(SecurityUtil.getUserId(), id,
                request.getOptions(), Boolean.TRUE.equals(request.getClose())));
    }
    
    @Operation(summary = "接龙",
            description = "向一条接龙消息追加一个自己的条目（text 为空回落为本人昵称）；close=true 由发起人截止接龙。"
                    + "服务端在 Redis 短锁下串行写 extra.chainItems，改完推全量快照给会话全员")
    @RateLimit(count = 120, dimension = RateLimit.Dimension.USER, key = "message.chain")
    @PostMapping("/{id}/chain")
    public Result<MessageVO> chain(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                   @RequestBody @Valid ChainRequest request) {
        return Result.ok(messageService.chain(SecurityUtil.getUserId(), id,
                request.getText(), Boolean.TRUE.equals(request.getClose())));
    }

    @Operation(summary = "群文件 / 群相册",
            description = "按群会话 + 消息类型分页取沉淀的附件消息：msgType=2 为群相册（图片）、3 为群文件；仅群成员可查，按发送时间倒序")
    @GetMapping("/group-media")
    public Result<PageResult<MessageVO>> groupMedia(
            @Parameter(description = "群会话 ID") @RequestParam("conversationId") Long conversationId,
            @Parameter(description = "消息类型：2 图片（群相册）/ 3 文件（群文件）") @RequestParam("msgType") Integer msgType,
            @Parameter(description = "页码，从 1 开始") @RequestParam(value = "current", defaultValue = "1") Long current,
            @Parameter(description = "每页条数，最大 100") @RequestParam(value = "size", defaultValue = "20") Long size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);
        return Result.ok(messageService.pageConversationMedia(SecurityUtil.getUserId(), conversationId, msgType, query));
    }

    @Operation(summary = "群精华消息列表",
            description = "按群会话分页取被群主/管理员标为精华的消息，按标记时间倒序；仅群成员可查")
    @GetMapping("/group-essence")
    public Result<PageResult<MessageVO>> groupEssence(
            @Parameter(description = "群会话 ID") @RequestParam("conversationId") Long conversationId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(value = "current", defaultValue = "1") Long current,
            @Parameter(description = "每页条数，最大 100") @RequestParam(value = "size", defaultValue = "20") Long size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);
        return Result.ok(messageService.pageEssence(SecurityUtil.getUserId(), conversationId, query));
    }

    @Operation(summary = "标记 / 取消精华消息",
            description = "仅群聊会话、仅群主与管理员可操作；essence=true 设为精华（幂等）、false 取消；变更后推 essence-notify 给群成员")
    @RateLimit(count = 60, dimension = RateLimit.Dimension.USER, key = "message.essence")
    @PutMapping("/{id}/essence")
    public Result<MessageVO> markEssence(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                         @RequestBody @Valid EssenceRequest request) {
        return Result.ok(messageService.markEssence(SecurityUtil.getUserId(), id,
                Boolean.TRUE.equals(request.getEssence())));
    }

    @Operation(summary = "我的收藏列表",
            description = "按收藏时间倒序分页取当前用户收藏的消息；conversationId 为空则跨全部会话。"
                    + "收藏属用户维度私有数据，只能看到自己的，已被自己删除的消息不再占行")
    @GetMapping("/favorites")
    public Result<PageResult<MessageVO>> favorites(
            @Parameter(description = "会话 ID，不传表示跨全部会话") @RequestParam(value = "conversationId", required = false) Long conversationId,
            @Parameter(description = "页码，从 1 开始") @RequestParam(value = "current", defaultValue = "1") Long current,
            @Parameter(description = "每页条数，最大 100") @RequestParam(value = "size", defaultValue = "20") Long size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);
        return Result.ok(messageService.pageFavorites(SecurityUtil.getUserId(), conversationId, query));
    }

    @Operation(summary = "标记 / 取消收藏消息",
            description = "用户维度的私人收藏，单聊群聊都可收、无需管理权限；favorite=true 收藏（幂等）、false 取消；"
                    + "变更后只推 favorite-notify 给本人，不打扰其他成员")
    @RateLimit(count = 60, dimension = RateLimit.Dimension.USER, key = "message.favorite")
    @PutMapping("/{id}/favorite")
    public Result<MessageVO> markFavorite(@Parameter(description = "消息 ID") @PathVariable("id") Long id,
                                          @RequestBody @Valid FavoriteRequest request) {
        return Result.ok(messageService.markFavorite(SecurityUtil.getUserId(), id,
                Boolean.TRUE.equals(request.getFavorite())));
    }

    @Operation(summary = "删除消息",
            description = "只对当前用户不可见，其他成员照常看到，与撤回是两件事；删除没有时间限制")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "消息 ID") @PathVariable("id") Long id) {
        messageService.deleteForUser(SecurityUtil.getUserId(), id);
        return Result.ok(null, "消息已删除");
    }

    @Operation(summary = "查看闪照",
            description = "打开预览前上报。采用「每人各看一次、互不影响」语义：把当前用户计入 flashViewedBy 名单，"
                    + "仅当会话全员都已消费才逻辑删原图记录（字节因秒传共享 objectKey 不物理删，但应用层再不可访问）；"
                    + "同一用户重复调用幂等，不会吃掉其他成员的查看机会")
    @RateLimit(count = 60, dimension = RateLimit.Dimension.USER, key = "message.flash-view")
    @PostMapping("/{id}/flash-view")
    public Result<MessageVO> flashView(@Parameter(description = "消息 ID") @PathVariable("id") Long id) {
        return Result.ok(messageService.flashView(SecurityUtil.getUserId(), id));
    }

    @Operation(summary = "清空会话聊天记录",
            description = "只对当前用户生效：批量写单端删除记录，此后历史 / 检索 / 离线都不再返回这些消息；"
                    + "其他成员不受影响，清空后对方再发的新消息照常可见")
    @DeleteMapping("/clear/{conversationId}")
    public Result<Void> clearConversation(@Parameter(description = "会话 ID") @PathVariable("conversationId") Long conversationId) {
        messageService.clearConversationForUser(SecurityUtil.getUserId(), conversationId);
        return Result.ok(null, "聊天记录已清空");
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
