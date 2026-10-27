package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 新建与编辑全服公告的入参（两者共用一个类）。
 *
 * <p>{@code publishNow} 而不是拆成「先建草稿、再调发布接口」两次请求：前端「发布公告」按钮
 * 期望的是一次点击一件事，而两次请求中间那次失败会在库里留下一条谁也不知道的草稿，
 * 管理员那边只有一个报错。所以发布在同一个方法里做完（先落库拿到 ID 再广播，
 * 顺序的理由见 {@code AnnouncementService#save}）。
 *
 * <p>没有 {@code status} 字段：状态不能由调用方随便填。草稿、已发布、已撤回之间的迁移
 * 各自有自己的规则（已发布不可编辑、已撤回不可再发布），这些走专门的端点，
 * 而不是一个「改状态」接口里靠 if 猜意图。
 */
@Data
@Schema(description = "新建/编辑全服公告入参")
public class AnnouncementSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED, example = "系统维护通知")
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 100, message = "公告标题不能超过 100 字符")
    private String title;

    @Schema(description = "正文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "公告正文不能为空")
    @Size(max = 2000, message = "公告正文不能超过 2000 字符")
    private String content;

    @Schema(description = "通知样式：info / success / warning / error，留空按 info", example = "warning")
    @Size(max = 16, message = "通知样式取值不合法")
    private String tone;

    @Schema(description = "失效时间，留空表示不失效；填写时必须晚于当前时间")
    private LocalDateTime expireTime;

    @Schema(description = "true 表示保存后立刻广播给全部在线连接；false 只存草稿", example = "false")
    private Boolean publishNow;
}
