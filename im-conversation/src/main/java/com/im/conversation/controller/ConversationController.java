package com.im.conversation.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.util.SecurityUtil;
import com.im.conversation.dto.req.CreateSingleRequest;
import com.im.conversation.dto.req.FlagRequest;
import com.im.conversation.dto.req.MarkReadRequest;
import com.im.conversation.dto.vo.ConversationVO;
import com.im.conversation.service.ConversationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话接口，全部需要登录。
 *
 * <p>{@code GET /unread/total} 与 {@code GET /{id}} 段数不同，Spring MVC 会优先匹配字面量路径，
 * 因此 {@code unread} 不会被误当成会话 ID 解析。
 *
 * <p>「删除会话」只隐藏本端列表项（{@code is_deleted=1}），消息与会话本身都不删：
 * 对方再次发消息时会自动重新露出，用户也不会因此丢失历史记录。
 */
@Tag(name = "03-会话", description = "会话列表、未读数、置顶、免打扰与单聊创建")
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
@SaCheckLogin
public class ConversationController {

    private final ConversationService conversationService;

    @Operation(summary = "会话列表",
            description = "过滤本端已隐藏的会话，排序为「置顶 > 置顶时间 > 最新消息时间」；已聚合对方/群的昵称头像、未读数、免打扰与最后消息摘要")
    @GetMapping("/list")
    public Result<List<ConversationVO>> list() {
        return Result.ok(conversationService.list(SecurityUtil.getUserId()));
    }

    @Operation(summary = "未读消息总数", description = "全部会话未读之和，服务端带 Redis 缓存，未读变动时主动失效")
    @GetMapping("/unread/total")
    public Result<Long> unreadTotal() {
        return Result.ok(conversationService.unreadTotal(SecurityUtil.getUserId()));
    }

    @Operation(summary = "会话详情", description = "非会话成员返回 4002")
    @GetMapping("/{id}")
    public Result<ConversationVO> detail(@Parameter(description = "会话 ID") @PathVariable("id") Long id) {
        return Result.ok(conversationService.detail(SecurityUtil.getUserId(), id));
    }

    @Operation(summary = "创建单聊会话",
            description = "校验好友关系与拉黑状态后幂等创建；A->B 与 B->A 命中同一条会话（biz_key = s:{小ID}:{大ID}）")
    @PostMapping("/single")
    public Result<Long> createSingle(@RequestBody @Valid CreateSingleRequest request) {
        return Result.ok(conversationService.createSingle(SecurityUtil.getUserId(), request.getTargetUserId()));
    }

    @Operation(summary = "标记会话已读",
            description = "清零未读、清除 @ 提醒并推进已读位点；请求体可省略表示整个会话已读，传 lastAckSeq 则只确认到该位点")
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@Parameter(description = "会话 ID") @PathVariable("id") Long id,
                                 @RequestBody(required = false) @Valid MarkReadRequest request) {
        Long lastAckSeq = request == null ? null : request.getLastAckSeq();
        conversationService.markRead(SecurityUtil.getUserId(), id, lastAckSeq);
        return Result.ok(null, "已标记为已读");
    }

    @Operation(summary = "置顶 / 取消置顶", description = "置顶会话始终排在列表最前，多个置顶按置顶时间倒序")
    @PutMapping("/{id}/top")
    public Result<Void> setTop(@Parameter(description = "会话 ID") @PathVariable("id") Long id,
                               @RequestBody @Valid FlagRequest request) {
        conversationService.setTop(SecurityUtil.getUserId(), id, Boolean.TRUE.equals(request.getEnabled()));
        return Result.ok(null, Boolean.TRUE.equals(request.getEnabled()) ? "已置顶" : "已取消置顶");
    }

    @Operation(summary = "消息免打扰开关", description = "免打扰只影响提醒，未读数仍然照常累计")
    @PutMapping("/{id}/mute")
    public Result<Void> setMute(@Parameter(description = "会话 ID") @PathVariable("id") Long id,
                                @RequestBody @Valid FlagRequest request) {
        conversationService.setMute(SecurityUtil.getUserId(), id, Boolean.TRUE.equals(request.getEnabled()));
        return Result.ok(null, Boolean.TRUE.equals(request.getEnabled()) ? "已开启免打扰" : "已关闭免打扰");
    }

    @Operation(summary = "隐藏 / 恢复会话",
            description = "enabled=true 隐藏本端会话（不删除消息），enabled=false 恢复显示；对方再发消息时隐藏状态会自动解除")
    @PutMapping("/{id}/hide")
    public Result<Void> setHide(@Parameter(description = "会话 ID") @PathVariable("id") Long id,
                                @RequestBody @Valid FlagRequest request) {
        conversationService.hide(SecurityUtil.getUserId(), id, Boolean.TRUE.equals(request.getEnabled()));
        return Result.ok(null, Boolean.TRUE.equals(request.getEnabled()) ? "已隐藏会话" : "已恢复会话");
    }

    @Operation(summary = "删除会话", description = "等价于隐藏本端会话，聊天记录与对方列表都不受影响")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "会话 ID") @PathVariable("id") Long id) {
        conversationService.hide(SecurityUtil.getUserId(), id, true);
        return Result.ok(null, "已删除会话");
    }
}
