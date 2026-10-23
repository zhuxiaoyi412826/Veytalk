package com.im.conversation.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.util.SecurityUtil;
import com.im.conversation.dto.req.QuickReplyRequest;
import com.im.conversation.service.QuickReplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 常用语（快捷回复）接口，全部需要登录、只操作本人那一份。
 *
 * <p>路径挂在 {@code /api/conversation} 下而不单开一个前缀：它是聊天输入框的附属功能，
 * 不是一类新资源，为二十条字符串开一个顶级模块不值得。
 *
 * <p>清单只存 Redis（零 DDL），因此这里没有分页与按 id 取单条——<b>读全部、写全部</b>就是它的用法。
 * 存储与取舍的理由见 {@link QuickReplyService}。
 */
@Tag(name = "03-会话", description = "常用语（快捷回复）")
@RestController
@RequestMapping("/api/conversation/quick-reply")
@RequiredArgsConstructor
@SaCheckLogin
public class QuickReplyController {

    private final QuickReplyService quickReplyService;

    @Operation(summary = "我的常用语",
            description = "按写入时的顺序返回，未配置过为空数组。聊天窗口每次打开面板时拉一次")
    @GetMapping
    public Result<List<String>> list() {
        return Result.ok(quickReplyService.list(SecurityUtil.getUserId()));
    }

    @Operation(summary = "整表替换常用语",
            description = "去首尾空白、丢空串、去重保序后写回；传空数组即清空。超长或超 20 条直接报错，不静默截断")
    @PutMapping
    public Result<List<String>> replace(@RequestBody @Valid QuickReplyRequest request) {
        return Result.ok(quickReplyService.replace(SecurityUtil.getUserId(), request.getReplies()));
    }
}
