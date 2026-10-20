package com.im.message.dto.vo;

import com.im.common.domain.MessageExtra;
import com.im.common.domain.QuotePreview;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息视图对象。
 *
 * <p>相比跨模块的 {@code MessageDTO} 多了两类前端渲染必需的字段：
 * 发送者资料（昵称头像，避免前端为每条消息单独查用户）与消息状态
 * （已发送 / 已送达 / 已读，气泡右下角的勾）。
 *
 * <p>已撤回的消息仍然会返回，但 {@code content} 与 {@code extra} 被清空、{@code recalled=true}：
 * 前端需要保留这条占位来显示「xx 撤回了一条消息」，若整条不返回，聊天记录会出现时间断层。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "消息")
public class MessageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "服务端消息 ID")
    private Long messageId;

    @Schema(description = "客户端消息 ID，用于与本地待发送消息对齐")
    private String clientMsgId;

    @Schema(description = "所属会话 ID")
    private Long conversationId;

    @Schema(description = "发送者 ID，0 表示系统通知")
    private Long fromUserId;

    @Schema(description = "发送者昵称，系统通知为「系统消息」")
    private String fromNickname;

    @Schema(description = "发送者头像")
    private String fromAvatar;

    @Schema(description = "消息类型：1 文本 2 图片 3 文件 4 语音 5 系统通知")
    private Integer msgType;

    @Schema(description = "消息类型中文描述")
    private String msgTypeDesc;

    @Schema(description = "消息内容，已撤回时为空")
    private String content;

    @Schema(description = "扩展信息，已撤回时为空")
    private MessageExtra extra;

    @Schema(description = "引用/回复的原消息预览，普通消息为 null")
    private QuotePreview quote;

    @Schema(description = "会话内自增序列，历史分页游标")
    private Long seq;

    @Schema(description = "消息状态：1 已发送 2 已送达 3 已读 4 已撤回")
    private Integer status;

    @Schema(description = "消息状态中文描述")
    private String statusDesc;

    @Schema(description = "是否已撤回")
    private Boolean recalled;

    @Schema(description = "撤回时间")
    private LocalDateTime recallTime;

    @Schema(description = "发送时间")
    private LocalDateTime sendTime;

    @Schema(description = "是否为当前登录用户发出，前端据此决定气泡左右")
    private Boolean self;

    @Schema(description = "已读人数，群聊展示「n 人已读」，单聊为 0 或 1")
    private Integer readCount;

    @Schema(description = "表情回应聚合列表，无回应时为空；已撤回消息不返回")
    private List<ReactionItemVO> reactions;

    @Schema(description = "投票聚合视图，仅投票消息非空；票数由服务端实时聚合而来")
    private VoteViewVO vote;

    @Schema(description = "是否为群精华消息（群主/管理员标记），仅群聊消息可能为 true")
    private Boolean essence;

    @Schema(description = "是否被当前查看者收藏（纯个人标记，别人收藏过什么不体现在这里）")
    private Boolean favorite;

    @Schema(description = "收藏时刻；只在 /message/favorites 分页里回填（列表按它倒序），其余接口为 null，sendTime 才是消息本身的发送时间")
    private LocalDateTime favoritedAt;
}
