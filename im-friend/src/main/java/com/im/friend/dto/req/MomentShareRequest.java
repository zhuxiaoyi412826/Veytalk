package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 把一条动态转成聊天消息（f12「动态转聊天」）。
 *
 * <p>{@link #toUserId} 与 {@link #conversationId} <b>二选一</b>，优先认 {@code conversationId}：
 * 前端从会话里点进来时手上有会话 ID，从动态详情点「发给谁」时只有对方 ID。
 * 两个都没填是 {@code 400}，不是「转发失败」——那是一个填表错误。
 *
 * <p>{@link #message} 是附言，不是动态正文的替代：转出去的气泡里永远带着原动态的快照，
 * 附言只是「我想让你看这个」那句话。
 *
 * <p><b>转发不改变可见范围</b>。收到气泡的人点进去时，服务端会按<b>他自己</b>与作者的关系
 * 重算一次可见性：不在受众里就报「这条动态不在了」。这是这个功能唯一的安全边界，
 * 而它必须在读侧而不是转发侧——转发侧拦不住「先转给好友、好友再截图」，但读侧能保证
 * 链接本身不出卖内容。
 */
@Data
@Schema(description = "动态转聊天请求")
public class MomentShareRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 附言上限：它是「看一眼这个」的引子，不是第二篇正文 */
    public static final int MESSAGE_MAX = 200;

    @Schema(description = "目标会话 ID（优先），为空时按 toUserId 找或建单聊会话")
    private Long conversationId;

    @Schema(description = "单聊目标用户 ID，conversationId 为空时必填", example = "194823748239849472")
    private Long toUserId;

    @Schema(description = "附言，可空", example = "这个像我上次说的那家")
    @Size(max = MESSAGE_MAX, message = "附言最多 " + MESSAGE_MAX + " 字")
    private String message;

    @Schema(description = "客户端消息 ID，用于幂等；不传由服务端生成")
    private String clientMsgId;
}
