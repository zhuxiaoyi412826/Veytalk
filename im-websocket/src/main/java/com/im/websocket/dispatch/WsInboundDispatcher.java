package com.im.websocket.dispatch;

import com.im.common.api.ResultCode;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.WsPacket;
import com.im.common.enums.MsgType;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.MessageSpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.TextUtil;
import com.im.websocket.dto.WsActionPayload;
import com.im.websocket.dto.WsChatPayload;
import com.im.websocket.manager.WsConnection;
import com.im.websocket.manager.WsSessionManager;
import com.im.websocket.service.WsPresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 上行报文路由：按 {@code type} 把帧分派到对应的处理逻辑。
 *
 * <p>之所以不让 {@code ImWebSocketHandler} 自己 switch，是因为 handler 处在容器回调栈上，
 * 它的职责必须窄到只有「收帧、解析、设 MDC、交出去」——任何业务判断写在里面，
 * 都会让人分不清某个异常到底该导致断连还是只回一个 error 报文。
 *
 * <h2>异常绝不能逃逸</h2>
 *
 * <p>这是本类最重要的一条约束。异常若抛回 Spring 的 {@code handleTextMessage}，
 * 容器会认为处理器无法继续，直接关闭连接。于是「发了条内容为空的消息」这种
 * 本该回一个 error 报文就完事的小问题，会变成用户莫名掉线、前端重连、再发一次、再掉线的死循环。
 * 所以 {@link #dispatch} 用一个总的 try 把所有异常收在这里，翻译成 error 报文回给客户端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsInboundDispatcher {

    private final WsPresenceService presenceService;
    private final WsSessionManager sessionManager;
    private final JsonUtil jsonUtil;

    /**
     * 消息模块是可选协作方：未装配时 WebSocket 仍然可以维持连接、心跳与在线状态，
     * 只是不能通过长连接收发消息，客户端会退回 REST 通道。
     */
    private final ObjectProvider<MessageSpi> messageSpiProvider;

    /**
     * 分派一个上行帧。
     *
     * <p>入参是原始文本而不是已解析的报文，目的是让 JSON 解析也落在同一个异常边界内。
     * 否则 handler 就得自己再搭一套 try-catch 去处理「报文根本不是合法 JSON」，
     * 两套处理逻辑一旦不一致（例如一套回 error 报文、另一套直接断连），
     * 前端就会对同类型的错误看到两种截然不同的行为。
     *
     * @param connection 发送方连接，身份取自握手票据，是后续所有权限判断的唯一依据
     * @param payload    帧的原始文本
     */
    public void dispatch(WsConnection connection, String payload) {
        String type = null;
        String clientMsgId = null;
        try {
            if (TextUtil.isBlank(payload)) {
                // 空帧多是客户端探活或中间代理插入的，为它回一个 error 只会制造噪声
                return;
            }
            WsPacket packet = jsonUtil.fromJson(payload, WsPacket.class);
            if (packet == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "报文为空");
            }
            type = packet.getType();
            clientMsgId = packet.getClientMsgId();
            route(connection, packet);
        } catch (BusinessException e) {
            // 业务异常是预期内的拒绝，留 warn 就够；完整堆栈对排查「为什么这条消息没发出去」没有帮助
            log.warn("WebSocket 上行处理被拒绝: userId={}, type={}, clientMsgId={}, code={}, message={}",
                    connection.userId(), type, clientMsgId, e.getCode(), e.getMessage());
            reject(connection, clientMsgId, e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("WebSocket 上行处理异常: userId={}, type={}, clientMsgId={}",
                    connection.userId(), type, clientMsgId, e);
            reject(connection, clientMsgId, ResultCode.SYSTEM_ERROR.getCode(),
                    ResultCode.SYSTEM_ERROR.getMessage());
        }
    }

    /**
     * 按报文类型路由。异常不在这里接，统一由 {@link #dispatch} 翻译成 error 报文。
     */
    private void route(WsConnection connection, WsPacket packet) {
        WsMessageType type = WsMessageType.of(packet.getType());
        if (type == null) {
            reject(connection, packet.getClientMsgId(), ResultCode.BAD_REQUEST.getCode(),
                    "未知的报文类型: " + packet.getType());
            return;
        }
        switch (type) {
            case CHAT -> handleChat(connection, packet);
            case DELIVERED -> handleDelivered(connection, packet.getData());
            case READ -> handleRead(connection, packet.getData());
            case RECALL -> handleRecall(connection, packet.getData());
            case PING -> presenceService.onHeartbeat(connection);
            case PULL_OFFLINE -> presenceService.pullOffline(connection);
            // 剩下的全是下行类型。客户端发这些过来，要么是前端写错了枚举，
            // 要么是有人在探协议边界，两种情况都只回错误、不断连
            default -> reject(connection, packet.getClientMsgId(), ResultCode.BAD_REQUEST.getCode(),
                    "报文类型 " + type.getType() + " 不支持上行");
        }
    }

    /**
     * 发送聊天消息。
     *
     * <p>成功后<strong>不回 ACK</strong>。{@code MessageServiceImpl.pushNewMessage}
     * 已经在事务提交之后把 ACK 推给了发送者的全部设备端——那份 ACK 是「消息已落库」的凭证，
     * 必须由真正写库的那一方发出。这里再回一次，同一条消息就会有两个 ACK：
     * 一个来自发起请求的这一端，一个来自该用户的其他端。多端同步恰恰依赖「其他端也收到 ACK」，
     * 少不得；而重复的 ACK 会让前端把本地那条 pending 消息匹配两次。
     */
    private void handleChat(WsConnection connection, WsPacket packet) {
        MessageSpi messageSpi = requireMessageSpi();
        WsChatPayload payload = jsonUtil.convert(packet.getData(), WsChatPayload.class);
        if (payload == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "chat 报文载荷无法解析");
        }
        // 外层与 data 里都可能带 clientMsgId，以 data 内的为准，缺失时回退到外层，
        // 免得前端两处都填、又因为其中一处漏填而丢掉幂等保护
        String clientMsgId = TextUtil.isNotBlank(payload.getClientMsgId())
                ? payload.getClientMsgId()
                : packet.getClientMsgId();
        if (TextUtil.isBlank(clientMsgId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "clientMsgId 不能为空");
        }
        MsgType msgType = MsgType.of(payload.getMsgType());
        if (msgType == MsgType.SYSTEM) {
            // 系统通知在客户端渲染成居中的灰字，且不做好友关系校验。
            // 允许客户端自选这个类型，等于允许任何人往任意会话里插一条看起来像官方通知的消息
            throw new BusinessException(ResultCode.BAD_REQUEST, "系统通知消息只能由服务端产生");
        }

        MessageSendCmd cmd = MessageSendCmd.builder()
                .clientMsgId(clientMsgId)
                .conversationId(payload.getConversationId())
                // 发送者只认连接身份。这是整条链路上唯一不可被客户端影响的一个字段
                .fromUserId(connection.userId())
                .toUserId(payload.getToUserId())
                .toGroupId(payload.getToGroupId())
                .msgType(msgType.getCode())
                .content(payload.getContent())
                .extra(payload.getExtra())
                .atUserIds(payload.getAtUserIds())
                .atAll(payload.getAtAll())
                // 显式钉死，不依赖 builder 的默认值：这两个开关决定要不要跳过好友与禁言校验，
                // 写出来比藏在 @Builder.Default 里更容易在 review 时被看到
                .internal(Boolean.FALSE)
                .push(Boolean.TRUE)
                .build();
        messageSpi.send(cmd);
    }

    /**
     * 送达上报。空列表直接忽略而不报错：前端在会话切换时很容易发出一个空的批量上报，
     * 为它回一个 error 报文只会让客户端日志里充满噪音。
     */
    private void handleDelivered(WsConnection connection, Object data) {
        WsActionPayload payload = parseAction(data);
        if (payload.getMessageIds() == null || payload.getMessageIds().isEmpty()) {
            return;
        }
        requireMessageSpi().markDelivered(connection.userId(), payload.getMessageIds());
    }

    /**
     * 已读上报。消息模块会顺带把已读回执推给发送方，所以这里不需要再回任何报文。
     */
    private void handleRead(WsConnection connection, Object data) {
        WsActionPayload payload = parseAction(data);
        if (payload.getConversationId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "read 报文缺少 conversationId");
        }
        // maxSeq 允许为空，语义是「整个会话全部已读」，这是前端点开一个会话时最常见的上报形式
        requireMessageSpi().markRead(connection.userId(), payload.getConversationId(), payload.getMaxSeq());
    }

    /**
     * 撤回消息。
     *
     * <p>时限与权限全部由消息模块判定（2 分钟内、普通成员只能撤自己的、群主与管理员可撤群内任意消息），
     * 这里只负责把操作人 ID 传下去。撤回成功的通知也由消息模块推给全体成员，
     * 本类不参与——它并不掌握会话成员名单。
     */
    private void handleRecall(WsConnection connection, Object data) {
        WsActionPayload payload = parseAction(data);
        if (payload.getMessageId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "recall 报文缺少 messageId");
        }
        requireMessageSpi().recall(payload.getMessageId(), connection.userId());
    }

    private WsActionPayload parseAction(Object data) {
        WsActionPayload payload = jsonUtil.convert(data, WsActionPayload.class);
        if (payload == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "报文载荷无法解析");
        }
        return payload;
    }

    private MessageSpi requireMessageSpi() {
        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        if (messageSpi == null) {
            // 报一个带说明的错误码而不是抛空指针：前端能据此提示「服务暂不可用」并退回 REST 通道
            throw new BusinessException(ResultCode.SYSTEM_ERROR.getCode(), "消息服务未装配");
        }
        return messageSpi;
    }

    /**
     * 回一个 {@code error} 报文。
     *
     * <p>发送失败不重试也不上报：能走到这里说明刚刚已经成功收到并解析了对端的帧，
     * 推不回去只可能是连接在这几毫秒内断了，而断开流程会自己把注册表清干净。
     */
    private void reject(WsConnection connection, String clientMsgId, int code, String message) {
        sessionManager.send(connection, WsPacket.error(clientMsgId, code, message));
    }
}
