package com.im.websocket.dto;

import com.im.common.domain.MessageExtra;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * WebSocket 上行 {@code chat} 报文的业务载荷，字段与 REST 的 {@code SendMessageRequest} 一一对应。
 *
 * <p>之所以不直接把报文里的 {@code data} 反序列化成 {@code MessageSendCmd}，
 * 是因为后者是跨模块的内部指令对象，带着 {@code fromUserId}、{@code internal}、{@code push} 三个字段。
 * 若让它直接承接客户端 JSON，那么任何人只要在报文里写上 {@code "internal": true}，
 * 就能让服务端跳过好友关系校验与禁言校验，并把自己伪装成 {@code fromUserId} 指定的另一个人发言——
 * 这是一条从「能发聊天消息」直接通到「能以任意用户身份向任意会话发系统通知」的权限提升通道，
 * 而且不需要任何越权探测，报文格式本身就是入口。
 *
 * <p>因此这里只声明客户端真正有权决定的字段，由 {@code WsInboundDispatcher} 显式搬到
 * {@code MessageSendCmd} 上，{@code fromUserId} 一律取连接身份，{@code internal} 一律为 false。
 * 代价是两个类字段重复，但这条边界值这个重复。
 */
@Data
public class WsChatPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 客户端消息 ID，幂等键；重发同一条消息时必须保持不变，服务端据此去重 */
    private String clientMsgId;

    /** 会话 ID，与 toUserId / toGroupId 三选一，按此优先级取第一个非空的 */
    private Long conversationId;

    /** 单聊接收者 ID，服务端会自动获取或创建会话 */
    private Long toUserId;

    /** 群聊接收群 ID，服务端会自动获取或创建群会话 */
    private Long toGroupId;

    /** 消息类型：1 文本 2 图片 3 文件 4 语音。5（系统通知）只允许服务端内部产生，客户端传了也会被拒 */
    private Integer msgType;

    /** 消息内容：文本消息为正文，附件类消息为文件 ID */
    private String content;

    /** 扩展信息。附件的宽高、时长、大小等元数据由服务端按 fileId 回填覆盖，客户端填了也不算 */
    private MessageExtra extra;

    /** 被 @ 的用户 ID 列表，仅群聊有效 */
    private List<Long> atUserIds;

    /** 是否 @ 全员，仅群聊有效 */
    private Boolean atAll;
}
