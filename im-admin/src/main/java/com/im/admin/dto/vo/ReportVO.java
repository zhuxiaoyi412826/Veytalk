package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报工单（举报人视角）。
 *
 * <p>只回举报人自己那份。默认<b>不含处置人信息</b>：管理员是谁对普通用户没有意义，
 * 而它一旦出现在响应里就会被截图当成「平台内部人员名录」用。
 * 只有打开 {@code im.admin.report-handler-visible} 后 {@code handlerName} 才会非空（见其配置注释）。
 *
 * <p>{@code targetTitle} / {@code targetExcerpt} 来自提交那一刻的证据快照，
 * 不是实时去查对象——消息可能早被撤回，实时查会让用户看到「内容不存在」而无从对证自己报了什么。
 */
@Data
@Schema(description = "举报工单（举报人视角）")
public class ReportVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "工单 ID")
    private Long id;

    @Schema(description = "被举报对象类型")
    private String targetType;

    @Schema(description = "对象类型的中文名")
    private String targetTypeName;

    @Schema(description = "被举报对象 ID")
    private Long targetId;

    @Schema(description = "对象的一句话标识（快照）")
    private String targetTitle;

    @Schema(description = "对象内容摘要（快照），无正文为空")
    private String targetExcerpt;

    @Schema(description = "举报理由码")
    private String reasonCode;

    @Schema(description = "举报理由中文名")
    private String reasonName;

    @Schema(description = "本人填写的补充描述")
    private String reasonText;

    @Schema(description = "状态：0 待处理 1 已受理 2 已驳回 3 已处置")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusName;

    @Schema(description = "处置动作码，未处置为空")
    private String disposition;

    @Schema(description = "处置动作中文名，未处置为空")
    private String dispositionName;

    @Schema(description = "处置说明：回给举报人的那句话，未处置为空")
    private String handleRemark;

    @Schema(description = "举报时间")
    private LocalDateTime createTime;

    @Schema(description = "处理时间，未处理为空")
    private LocalDateTime handleTime;

    @Schema(description = "处置人昵称：仅当后台开启「处置人可见」且已有人处置时非空，否则为空")
    private String handlerName;
}
