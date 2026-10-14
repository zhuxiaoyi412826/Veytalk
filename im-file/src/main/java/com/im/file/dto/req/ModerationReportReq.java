package com.im.file.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 图片审核命中上报。
 *
 * <p>识别在发送端跑，被拦下的图片字节从未离开本机，所以这里只能上报「判定结果」而不是图片本身。
 * 内容全部来自客户端，服务端不采信其中的判定结论——它只用于留痕，写审计通道时按字段长度截断、
 * 概率强制收敛到 0~1，异常值不会污染日志格式，但也不会拿它做任何业务判断。
 *
 * <p>上报是尽力而为：前端发失败不重试也不提示用户，被拦下这件事本身已经在本地生效了。
 */
@Data
@Schema(description = "图片审核命中上报")
public class ModerationReportReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "业务场景，取文件业务类型：avatar / chat_image / video_cover",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "chat_image")
    @NotBlank(message = "业务场景不能为空")
    @Size(max = 32, message = "业务场景过长")
    private String scene;

    @Schema(description = "命中的类别名（模型输出原名）", requiredMode = Schema.RequiredMode.REQUIRED, example = "Porn")
    @NotBlank(message = "命中的类别不能为空")
    @Size(max = 32, message = "类别名过长")
    private String hitClass;

    @Schema(description = "命中类别的概率", requiredMode = Schema.RequiredMode.REQUIRED, example = "0.93")
    @NotNull(message = "命中概率不能为空")
    @DecimalMin(value = "0", message = "命中概率必须在 0~1 之间")
    @DecimalMax(value = "1", message = "命中概率必须在 0~1 之间")
    private Double hitProbability;

    @Schema(description = "上报时前端实际使用的阈值，用于事后核对阈值改动的影响", example = "0.8")
    @DecimalMin(value = "0", message = "阈值必须在 0~1 之间")
    @DecimalMax(value = "1", message = "阈值必须在 0~1 之间")
    private Double threshold;

    @Schema(description = "原始文件名（图片尚未上传，服务端拿不到，只能由客户端报）", example = "photo.jpg")
    @Size(max = 200, message = "文件名过长")
    private String fileName;

    @Schema(description = "文件字节数", example = "1048576")
    @DecimalMin(value = "0", message = "文件大小不能为负")
    private Long fileSize;

    @Schema(description = "会话 ID，聊天场景便于定位是哪个会话里试过；单聊/群聊都填")
    private Long conversationId;

    @Schema(description = "模型输出的完整评分，按概率从高到低；只用于审计留痕")
    @Size(max = 16, message = "评分条目过多")
    @Valid
    private List<Score> scores;

    /**
     * 单个类别的评分。
     */
    @Data
    @Schema(description = "单个类别的评分")
    public static class Score implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "类别名", example = "Sexy")
        @NotBlank(message = "类别名不能为空")
        @Size(max = 32, message = "类别名过长")
        private String className;

        @Schema(description = "概率", example = "0.42")
        @NotNull(message = "概率不能为空")
        @DecimalMin(value = "0", message = "概率必须在 0~1 之间")
        @DecimalMax(value = "1", message = "概率必须在 0~1 之间")
        private Double probability;
    }
}
