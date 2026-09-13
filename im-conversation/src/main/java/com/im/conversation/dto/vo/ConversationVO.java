package com.im.conversation.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话列表 / 详情视图。
 *
 * <p>名称与头像的取值随会话类型不同：单聊取对方资料（有好友备注时优先展示备注），
 * 群聊取群名称与群头像，因此服务端已经把差异抹平，前端只需渲染 {@code name} 与 {@code avatar}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话信息")
public class ConversationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID")
    private Long conversationId;

    @Schema(description = "会话类型：1 单聊 2 群聊")
    private Integer type;

    @Schema(description = "会话类型中文描述")
    private String typeDesc;

    @Schema(description = "会话目标：单聊为对方用户 ID，群聊为群 ID")
    private Long targetId;

    @Schema(description = "展示名称：单聊为备注或对方昵称，群聊为群名")
    private String name;

    @Schema(description = "展示头像")
    private String avatar;

    @Schema(description = "对方是否在线，仅单聊有意义")
    private Boolean online;

    @Schema(description = "我对对方的好友备注，仅单聊")
    private String remark;

    @Schema(description = "未读消息数")
    private Integer unreadCount;

    @Schema(description = "是否有未读的 @ 提醒，仅群聊")
    private Boolean atFlag;

    @Schema(description = "最后一条消息 ID")
    private Long lastMsgId;

    @Schema(description = "最后一条消息摘要")
    private String lastMsgContent;

    @Schema(description = "最后一条消息类型：1 文本 2 图片 3 文件 4 语音 5 系统通知")
    private Integer lastMsgType;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastMsgTime;

    @Schema(description = "是否置顶")
    private Boolean top;

    @Schema(description = "置顶时间")
    private LocalDateTime topTime;

    @Schema(description = "是否消息免打扰")
    private Boolean muted;

    @Schema(description = "本端是否已隐藏该会话")
    private Boolean hidden;

    @Schema(description = "已读位点 seq，前端据此计算需要拉取的离线消息")
    private Long lastAckSeq;

    @Schema(description = "成员数，仅群聊")
    private Integer memberCount;

    @Schema(description = "我在群内的角色：1 群主 2 管理员 3 成员，仅群聊")
    private Integer myRole;
}
