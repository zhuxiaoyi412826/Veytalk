package com.im.message.service;

import com.im.common.api.PageResult;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageSendCmd;
import com.im.message.dto.req.ForwardMessageRequest;
import com.im.message.dto.req.MessageSearchQuery;
import com.im.message.dto.req.SendMessageRequest;
import com.im.message.dto.vo.MessageVO;

import java.util.Collection;
import java.util.List;

/**
 * 消息服务。
 *
 * <p>发送、撤回、已读这三条链路同时被 REST 接口与 {@code MessageSpiImpl}（WebSocket 入口）调用，
 * 因此接口上只保留一份实现：REST 层负责把 {@link SendMessageRequest} 翻译成 {@link MessageSendCmd}，
 * 之后走的是完全相同的校验与落库路径，不存在「HTTP 发得出去、WS 发不出去」的行为差异。
 */
public interface MessageService {

    /* ==================== 发送 ==================== */

    /**
     * 发送消息核心入口：定位会话 -&gt; 权限校验 -&gt; 幂等判重 -&gt; 内容规范化 -&gt; 发号落库
     * -&gt; 回调会话模块 -&gt; WebSocket 推送。
     *
     * @return 落库后的消息，重复提交时返回首次的结果
     */
    MessageDTO send(MessageSendCmd cmd);

    /**
     * REST 发送入口，返回值直接可渲染，省去前端再查一次消息详情。
     */
    MessageVO sendForView(Long userId, SendMessageRequest request);

    /**
     * 发送系统通知，发送者为虚拟系统账号，跳过好友与禁言校验。
     */
    MessageDTO sendSystemNotice(Long conversationId, String content);

    /* ==================== 查询 ==================== */

    /**
     * 按 ID 查询消息，不存在返回 {@code null}。
     */
    MessageDTO findDto(Long messageId);

    /**
     * 用户能否看到引用了指定文件的消息，供文件模块做下载鉴权。
     *
     * <p>判定口径是「两人共同所在的会话里存在引用该文件的未撤回附件消息」：
     * 附件消息的 {@code content} 存的就是文件 ID，且必由上传者本人发出（发送时已校验归属），
     * 因此不需要解析 extra JSON，也不需要把任何一侧的全部消息捞回内存比对。
     */
    boolean fileVisibleTo(Long fileId, Long uploaderId, Long viewerId);

    /**
     * 历史消息游标分页，按 seq 升序返回，已排除当前用户单端删除的消息。
     *
     * @param beforeSeq 游标，为空表示从最新一条开始
     * @param size      本页条数
     */
    List<MessageVO> history(Long userId, Long conversationId, Long beforeSeq, int size);

    /**
     * 会话内消息内容检索。
     */
    PageResult<MessageVO> search(Long userId, MessageSearchQuery query);

    /**
     * 离线消息（前端视图），按会话与 seq 升序，返回后自动推进已确认位点。
     */
    List<MessageVO> offlineView(Long userId);

    /**
     * 离线消息（跨模块传输对象），行为与 {@link #offlineView} 一致。
     */
    List<MessageDTO> offlineDto(Long userId);

    /* ==================== 状态变更 ==================== */

    /**
     * 撤回消息：2 分钟内可撤回，本人或群管理员可操作。
     */
    void recall(Long messageId, Long operatorId);

    /**
     * 单端删除消息，只对操作者本人不可见。
     */
    void deleteForUser(Long userId, Long messageId);

    /**
     * 批量送达上报，写入 {@code im_message_read.delivered_time} 并通知发送方。
     */
    void markDelivered(Long userId, Collection<Long> messageIds);

    /**
     * 按位点批量已读：写入 {@code read_time} 并把已读回执推送给各条消息的发送方。
     *
     * @param maxSeq 已读到的位点，为空表示整个会话
     */
    void markRead(Long userId, Long conversationId, Long maxSeq);

    /**
     * 清除离线标记：把用户在全部会话上的已确认位点推进到当前最大 seq。
     *
     * <p>只推进位点、不清未读数——客户端拉到消息不等于用户看过，未读红点要等
     * 用户真正点开会话触发 {@code markRead} 才消失。
     */
    void clearOffline(Long userId);

    /* ==================== 转发 ==================== */

    /**
     * 转发消息：把一条已存在的消息复制到目标会话。
     *
     * <p>转发不携带引用（quoteMsgId 为空），收到的消息就是一条普通的新消息。
     * 附件类消息复用原文件 ID，不重新上传；转发者必须是原会话成员，
     * 否则任何人都能通过猜 messageId 把别人会话里的文件广播出去。
     *
     * @param userId  转发操作人
     * @param request 转发请求（原消息 ID + 目标会话）
     * @return 落库后的新消息（跨模块传输对象，供 SPI 调用）
     */
    MessageDTO forwardDto(Long userId, ForwardMessageRequest request);

    /**
     * REST 转发入口，返回值直接可渲染。
     */
    MessageVO forward(Long userId, ForwardMessageRequest request);
}
