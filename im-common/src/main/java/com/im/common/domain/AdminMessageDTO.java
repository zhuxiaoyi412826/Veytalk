package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条消息的后台视角（{@code im_message}），由 im-message 经 {@code AdminMessageSpi} 提供。
 *
 * <p>与用户端 {@code MessageVO} 的三点关键差别：
 * <ol>
 *   <li><b>不过滤单端删除</b>：用户端会按 {@code im_message_delete} 把「这个人已清空过」的行藏掉，
 *       后台读的是原始行——取证时不能因为某人点了「清空聊天记录」就查无此消息；</li>
 *   <li><b>已撤回的行照常给出</b>，并带上 {@code recallTime}，撤回动作本身是要看的事件；</li>
 *   <li>不带 {@code self} / {@code favorite} / {@code essence} 这些「查看者相对」的字段：
 *       后台没有查看者立场，那些列在这里只会是 null。</li>
 * </ol>
 *
 * <p>{@code content} 的口径随类型变：文本类是正文，图片/文件/语音那一类<b>存的是文件 ID</b>
 * （不是 URL），所以后台把它原样显示成「文件 12345」而不是试图渲染成缩略图——
 * 想看图要走文件下载那条路，而那条路的鉴权是「查看者必须是上传者的会话成员」，
 * 管理员凭这一屏的权限<b>不该</b>拿到别人会话里的附件本体。这一条是有意的越权边界。
 *
 * <p>超长正文由实现方截到 2000 字符并置 {@code contentTruncated=true}：
 * 一屏二十行各带一段复制粘贴的堆栈信息会把响应撑爆，而完整正文的点查在档案接口里给。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMessageDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息 ID（雪花） */
    private Long messageId;

    /** 所属会话 ID */
    private Long conversationId;

    /** 会话名称（单聊取对方昵称，群聊取群名；查不到为 null） */
    private String conversationName;

    /** 会话类型：1 单聊 2 群聊，见 {@code ConvType} */
    private Integer conversationType;

    /** 发送者 ID，0 表示系统通知 */
    private Long fromUserId;

    /** 发送者昵称；系统消息给「系统」 */
    private String fromName;

    /** 类型码：1~14，见 {@code MsgType} */
    private Integer msgType;

    /** 类型中文名（实现方从 MsgType 枚举取，前端不复制一份码表） */
    private String msgTypeDesc;

    /** 正文或文件 ID 等，可能已截断，见类注释 */
    private String content;

    /** 正文是否被截断 */
    private Boolean contentTruncated;

    /** 正文字符数（截断前的原值，来自实现方数一次 length） */
    private Integer contentLength;

    /** {@code extra} 的可读摘要（@列表、附件名、通话时长等），无扩展信息为 null */
    private String extraSummary;

    /** 会话内序号 */
    private Long seq;

    /** 引用/回复的原消息 ID，转发为空 */
    private Long quoteMsgId;

    /** 引用的那条消息的一句话摘要，仅档案给出（列表不额外查一次） */
    private String quoteSummary;

    /** 是否已撤回 */
    private Integer recalled;

    /** 撤回时间 */
    private LocalDateTime recallTime;

    /** 发送时间（服务端落库时间） */
    private LocalDateTime sendTime;

    /**
     * 已读人数：仅单聊有意义（{@code im_message_read} 逐条写回执），
     * 群聊为 null——群聊已读由 {@code im_conversation_member.last_read_seq} 推算，
     * 逐条消息算一次要扫成员表，后台这一屏不值那个钱。
     */
    private Integer readCount;
}
