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
 *   <li>转账：transferAmount / transferToUserId / transferToNickname / transferMemo</li>
 *   <li>红包：packetId / packetAmount / packetCount / packetGreeting（领取状态不落这里）</li>
 *   <li>通话记录：callMediaType / duration / callScreenRecorded / fileId，群通话另带 callParticipantCount</li>
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

    /**
     * 参与人数（仅群通话记录）：{@code null} 表示这是一路 1:1 通话，页面不显示人数；
     * 有值则渲染成「群语音通话 · 4 人」。
     *
     * <p>只存一个整数而不存参与者 ID 清单：通话记录是「几点打了多久」的凭证，
     * 具体哪几个人参加过属于可变数据（成员事后退群就变成假名单），而群内查人随时能查。
     */
    @Schema(description = "参与人数（仅群通话记录）：为 null 表示 1:1 通话")
    private Integer callParticipantCount;

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

    @Schema(description = "转账：金额（积分，正数）。服务端在扣款成功后写入，不接受客户端上报")
    private Long transferAmount;

    @Schema(description = "转账：收款人用户 ID")
    private Long transferToUserId;

    @Schema(description = "转账：收款人昵称快照（发送时定格，付款方气泡无需再查库就能写「转给谁」）")
    private String transferToNickname;

    @Schema(description = "转账：附言（已经 XSS 清洗与敏感词遮蔽，可空）")
    private String transferMemo;

    @Schema(description = "红包：红包 ID（兼作 Redis 键与账本流水的 ref_id），领取时按此寻址")
    private Long packetId;

    @Schema(description = "红包：总额（积分，正数）")
    private Long packetAmount;

    @Schema(description = "红包：份数；私聊恒为 1（独占），群聊可多份拼手气")
    private Integer packetCount;

    @Schema(description = "红包：祝福语（可空，默认「恭喜发财，大吉大利」）")
    private String packetGreeting;

    @Schema(description = "接龙：主题 / 引导语（发送时定格，限长）")
    private String chainTitle;

    @Schema(description = "接龙：有序条目列表，列表下标即序号；服务端 read-modify-write 维护，发送时恒为空")
    private List<ChainItem> chainItems;

    @Schema(description = "接龙：是否已由发起人截止（截止后不再接受新条目）")
    private Boolean chainClosed;

    /**
     * 是否包含 @ 提醒，供会话模块判断是否需要打破免打扰。
     */
    public boolean hasMention() {
        return Boolean.TRUE.equals(atAll) || (atUserIds != null && !atUserIds.isEmpty());
    }
}
