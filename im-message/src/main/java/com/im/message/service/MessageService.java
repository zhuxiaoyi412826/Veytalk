package com.im.message.service;

import com.im.common.api.PageResult;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.PageQuery;
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
     * 导出当前用户在某会话的完整聊天记录（服务端权威数据，非本地缓存）。
     *
     * <p>与 {@link #history} 游标分页不同，本方法一次性把整个会话的消息按 seq 正序取回（上限
     * {@code ImConstants.MAX_EXPORT_MESSAGES} 条），同样排除当前用户单端删除的消息，供前端
     * 拼装为 JSON/CSV 下载。仅会话成员可导，用于合规备份/迁移与全量导出。
     */
    List<MessageVO> exportConversation(Long userId, Long conversationId);

    /**
     * 会话内消息内容检索。
     */
    PageResult<MessageVO> search(Long userId, MessageSearchQuery query);

    /**
     * 语音转文字（依赖外部 ASR 服务，未接入时优雅降级）。
     *
     * <p>仅未撤回的语音消息可用，且调用者必须是会话成员。无可用 SPI 实时时抛
     * {@code MESSAGE_TO_TEXT_UNAVAILABLE}；返回的文本仅展示不落库（不回写 extra）。
     */
    String toText(Long userId, Long messageId);

    /**
     * 消息翻译（依赖外部翻译服务，未接入时优雅降级）。
     *
     * <p>仅未撤回的文本消息可用，且调用者必须是会话成员。{@code targetLang} 为空时回退配置默认目标语言；
     * 无可用 SPI 实现时抛 {@code MESSAGE_TRANSLATE_UNAVAILABLE}。译文仅返回展示，不改写原消息。
     */
    String translate(Long userId, Long messageId, String targetLang);

    /**
     * 群文件 / 群相册：按会话 + 消息类型（图片 {@code 2} / 文件 {@code 3}）分页取群内沉淀的附件消息。
     *
     * <p>仅群聊会话可用，且查看者必须是群成员；结果按发送时间倒序，已排除单端删除与已撤回的消息。
     * 这是 {@code im_message} 上的一份过滤视图，无额外存储。
     */
    PageResult<MessageVO> pageConversationMedia(Long userId, Long conversationId, Integer msgType, PageQuery query);

    /**
     * 精华消息列表：按会话分页取被群主/管理员标记为精华的消息，按标记时间倒序。
     *
     * <p>仅群聊会话可用，且查看者必须是群成员；底层为 {@code im_message_essence} 关联 {@code im_message}。
     */
    PageResult<MessageVO> pageEssence(Long userId, Long conversationId, PageQuery query);

    /**
     * 标记 / 取消精华消息：仅群聊会话、仅群主与管理员可操作，被标记的消息须属于该群且未撤回。
     *
     * <p>{@code essence=true} 写入精华行（已是精华则幂等），{@code false} 删除精华行；变更后推
     * {@code essence-notify} 给群成员，各端气泡的精华标签实时增减。
     *
     * @return 含最新 essence 标记的消息视图
     */
    MessageVO markEssence(Long userId, Long messageId, boolean essence);

    /**
     * 收藏列表：按收藏时间倒序分页取当前用户的收藏消息，{@code conversationId} 非空时只看本会话。
     *
     * <p>收藏是用户维度的私人数据，所以这里<b>不验成员关系</b>（只按 user_id 取自己的行）。
     * 列表里的行仍然走同一套查看者视图：已被自己单端删除的不会返回（收藏行在删除时已级联清掉）；
     * <b>被发送者撤回的会留一行空内容占位</b>，因为用户需要能看到「这条我收过的消息已经没了」并能取消收藏。
     */
    PageResult<MessageVO> pageFavorites(Long userId, Long conversationId, PageQuery query);

    /**
     * 收藏 / 取消收藏：任何会话里未撤回的消息都能收，仅本人可见、无需管理权限。
     *
     * <p>{@code favorite=true} 写入收藏行（已收藏则幂等），{@code false} 删除本人那一行（别人对同一条
     * 消息的收藏不受影响）；变更后推 {@code favorite-notify} 给<b>操作者自己的其他设备</b>。
     *
     * @return 含最新 favorite 标记的消息视图
     */
    MessageVO markFavorite(Long userId, Long messageId, boolean favorite);

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
     * 管理侧强制撤回（举报处置入口）：跳过撤回时限与逐人权限校验。
     *
     * <p>与 {@link #recall} 共用同一条落库与广播链路，只放开「时限」和「权限」两道闸；
     * 资金类消息（转账 / 红包）与系统通知<b>仍然拒绝</b>——这两道拦的不是权限，
     * 而是「撤回会把已经生效的业务凭证抹掉」这件事本身。管理员身份由调用方（im-admin）
     * 经 {@code PERM_SYSTEM_MANAGE} 校验，本方法不再复查。
     *
     * <p>幂等：目标已撤回时静默返回，与 {@link #recall} 同口径。
     */
    void adminRecall(Long messageId, Long adminId);

    /**
     * 编辑消息：仅限发送者本人、时限内、且为未撤回的文本消息。
     *
     * <p>与撤回不同，编辑是就地改写正文并在 {@code extra} 上打 {@code edited} 标记（零 DDL），
     * 编辑后的正文重新过 XSS 清洗与敏感词遮蔽，并把新正文推给会话全员（含操作者其他设备）。
     *
     * @return 编辑后的最新消息视图
     */
    MessageVO edit(Long userId, Long messageId, String content);

    /**
     * 表情回应：对一条消息贴 / 取消一个表情（所有消息类型适用，已撤回消息不可）。
     *
     * <p>一人对一条消息的同一表情只计一次：{@code remove=false} 且已存则幂等不重复，
     * {@code remove=true} 则删掉本人这条回应。变更后推增量给会话全员。
     *
     * @return 含最新聚合回应的消息视图
     */
    MessageVO react(Long userId, Long messageId, String emoji, boolean remove);

    /**
     * 投票：对一条投票消息选择若干选项（重新投票先清后写）；{@code close=true} 时由发起人截止投票。
     *
     * <p>选项下标必须在定义范围内；单选只允许 1 个，多选去重后 ≥ 1。变更后推权威票数给会话全员。
     *
     * @return 含最新票数的消息视图
     */
    MessageVO vote(Long userId, Long messageId, List<Integer> options, boolean close);

    /**
     * 接龙：向一条接龙消息追加一个自己的条目（{@code text} 为空时回落为本人昵称）；
     * {@code close=true} 时由发起人截止接龙。
     *
     * <p>服务端在 Redis 短锁保护下 read-modify-write 重写 {@code extra.chainItems}（锁避免并发追加互相覆盖），
     * 改完推全量快照给会话全员。仅会话成员可参与，已截止或超过条目上限时拒绝。
     *
     * @return 含最新条目列表的消息视图（extra.chainItems）
     */
    MessageVO chain(Long userId, Long messageId, String text, boolean close);

    /**
     * 查看闪照：首次查看即销毁。
     *
     * <p>会话成员调用；服务端把 {@code extra.flashViewed} 置真并销毁原图文件记录，
     * 此后任何成员（含发送者）都无法再取回图片字节。重复调用幂等。
     *
     * @return 销毁后的最新消息视图（extra.flashViewed=true）
     */
    MessageVO flashView(Long userId, Long messageId);

    /**
     * 单端删除消息，只对操作者本人不可见。
     */
    void deleteForUser(Long userId, Long messageId);

    /**
     * 清空当前用户在某会话的全部聊天记录（单端）。
     *
     * <p>批量写单端删除记录，此后历史 / 检索 / 离线都不再向该用户返回这些消息；
     * 其他成员不受影响，清空后对方再发的新消息照常可见。
     */
    void clearConversationForUser(Long userId, Long conversationId);

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
