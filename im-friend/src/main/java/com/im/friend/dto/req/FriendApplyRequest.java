package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发起好友申请入参。
 *
 * <p>{@code targetUserId} 与 {@code targetAccount} 二选一，前者优先：
 * 从「用户资料卡片 / 搜索结果」进入时前端已持有 userId；
 * 从「添加好友」搜索框直接输入账号或手机号时走 targetAccount，由服务端解析为用户。
 */
@Data
@Schema(description = "好友申请入参")
public class FriendApplyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "目标用户 ID，与 targetAccount 二选一，前者优先", example = "1002")
    private Long targetUserId;

    @Schema(description = "目标账号或手机号，与 targetUserId 二选一", example = "bob")
    @Size(max = 64, message = "账号长度不能超过 64")
    private String targetAccount;

    @Schema(description = "验证消息，展示给被申请人", example = "我是 Alice，加个好友")
    @Size(max = 255, message = "验证消息长度不能超过 255")
    private String verifyMessage;

    @Schema(description = "申请来源：search / qrcode / group，默认 search", example = "search")
    @Size(max = 32, message = "来源标识长度不能超过 32")
    private String source;
}
