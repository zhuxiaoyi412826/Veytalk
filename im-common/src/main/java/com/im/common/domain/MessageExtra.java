package com.im.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * 消息扩展信息，落库时以 JSON 列存储（{@code im_message.extra}）。
 *
 * <p>按消息类型复用同一结构，未使用的字段保持 {@code null} 并在序列化时忽略，
 * 避免每条消息都存一份全字段空壳：
 * <ul>
 *   <li>图片：fileId / fileUrl / width / height / fileSize / contentType</li>
 *   <li>文件：fileId / fileName / fileUrl / fileSize / contentType / ext</li>
 *   <li>语音：fileId / fileUrl / duration / fileSize</li>
 *   <li>群聊 @：atUserIds / atAll</li>
 *   <li>系统通知：action（前端据此决定点击后的跳转行为）</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "消息扩展信息")
public class MessageExtra implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "关联文件 ID")
    private Long fileId;

    @Schema(description = "文件原始名")
    private String fileName;

    @Schema(description = "文件访问地址")
    private String fileUrl;

    @Schema(description = "文件字节数")
    private Long fileSize;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "扩展名，不含点")
    private String ext;

    @Schema(description = "图片宽度（像素）")
    private Integer width;

    @Schema(description = "图片高度（像素）")
    private Integer height;

    @Schema(description = "语音时长（秒）")
    private Integer duration;

    @Schema(description = "被 @ 的用户 ID 列表")
    private List<Long> atUserIds;

    @Schema(description = "是否 @ 全员")
    private Boolean atAll;

    @Schema(description = "系统通知动作标识，前端据此决定交互")
    private String action;

    @Schema(description = "图片/文件是否带发送者水印：为真时接收端查看该附件会叠加发送者昵称水印")
    private Boolean watermark;

    @Schema(description = "是否闪照（仅图片）：查看一次即销毁，销毁后服务端不再可下载原图")
    private Boolean flash;

    @Schema(description = "闪照查看时长（秒），倒计时由前端执行，到期仅关闭预览")
    private Integer flashSeconds;

    @Schema(description = "闪照已消费查看的成员 ID 名单：服务端权威，每人各有一次查看机会，互不影响；全员都看过才物理销毁原图")
    private List<Long> flashViewedBy;

    @Schema(description = "闪照对当前查看者是否已销毁：下发前按查看者从 flashViewedBy 现算的瞬态字段，不落库（List 里含自己即为已销毁）")
    private Boolean flashViewed;

    @Schema(description = "发送端 OCR 提取的图内文字（已经过敏感词遮蔽），供接收端「图内文字」功能展示")
    private String ocrText;

    @Schema(description = "通话媒体类型（仅通话记录）：audio 语音 / video 视频")
    private String callMediaType;

    @Schema(description = "通话是否录屏留存（仅通话记录）：为真时 content 指向的录制文件包含屏幕画面")
    private Boolean callScreenRecorded;

    @Schema(description = "文本消息是否已被发送者编辑过：编辑标记落 extra JSON 列，不新增表字段")
    private Boolean edited;

    @Schema(description = "最近一次编辑时间（仅 edited=true 时有值）")
    private LocalDateTime editTime;

    @Schema(description = "名片消息：被分享用户的 ID（服务端按此回填下两个快照字段，不信任客户端上报）")
    private Long cardUserId;

    @Schema(description = "名片消息：被分享用户昵称快照（发送时定格，接收端无需再查库）")
    private String cardNickname;

    @Schema(description = "名片消息：被分享用户头像快照")
    private String cardAvatar;

    @Schema(description = "位置消息：地址 / 地点文字描述")
    private String locationName;

    @Schema(description = "位置消息：纬度（-90~90）")
    private Double latitude;

    @Schema(description = "位置消息：经度（-180~180）")
    private Double longitude;

    @Schema(description = "合并转发：记录卡片标题（如 “XX 与 XX 的聊天记录”）")
    private String mergedTitle;

    @Schema(description = "合并转发：定格的消息快照列表，接收端展开查看器直接渲染")
    private List<MergedMessageItem> mergedMessages;

    @Schema(description = "投票：标题")
    private String voteTitle;

    @Schema(description = "投票：选项文本列表（静态定义，票数不在此）")
    private List<String> voteOptions;

    @Schema(description = "投票：是否多选")
    private Boolean voteMultiple;

    @Schema(description = "投票：是否匿名")
    private Boolean voteAnonymous;

    @Schema(description = "投票：是否已截止")
    private Boolean voteClosed;

    @Schema(description = "机器人消息：机器人名称快照（仅群机器人 Webhook 发出时写入，接收端据此覆盖发送者昵称）")
    private String robotName;

    @Schema(description = "机器人消息：机器人头像快照（仅群机器人 Webhook 发出时写入）")
    private String robotAvatar;

    /**
     * 是否包含 @ 提醒，供会话模块判断是否需要打破免打扰。
     */
    public boolean hasMention() {
        return Boolean.TRUE.equals(atAll) || (atUserIds != null && !atUserIds.isEmpty());
    }
}
