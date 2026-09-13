package com.im.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
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
@Builder
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

    /**
     * 是否包含 @ 提醒，供会话模块判断是否需要打破免打扰。
     */
    public boolean hasMention() {
        return Boolean.TRUE.equals(atAll) || (atUserIds != null && !atUserIds.isEmpty());
    }
}
