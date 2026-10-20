package com.im.admin.dto.vo;

import com.im.common.domain.ReportTargetDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 举报工单（管理员视角）。
 *
 * <p>比举报人那份多了三块判案必需的信息：谁报的（{@code reporter}）、报了谁（{@code reportedUser}）、
 * 以及完整的证据快照（{@code evidence}）。少一块都得再点一次详情、多打一次库。
 *
 * <p>{@code allowedDispositions} 直接放在行数据里：后台列表每行都有处置按钮，
 * 若让前端按 {@code targetType} 自己推，就把「哪个模块真能执行动作」这件事搬到了前端，
 * 模块缺席时前端会渲染出一个必然失败的按钮。这里给的是<b>提交那一刻</b>由 SPI 报上来的集合。
 */
@Data
@Schema(description = "举报工单（管理员视角）")
public class AdminReportVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "工单 ID")
    private Long id;

    @Schema(description = "对象类型码：user / message / room / group")
    private String targetType;

    @Schema(description = "对象类型中文名")
    private String targetTypeName;

    @Schema(description = "被举报对象 ID")
    private Long targetId;

    @Schema(description = "举报理由码")
    private String reasonCode;

    @Schema(description = "举报理由中文名")
    private String reasonName;

    @Schema(description = "举报人填的补充描述")
    private String reasonText;

    @Schema(description = "状态码")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusName;

    @Schema(description = "处置动作码，未处置为空")
    private String disposition;

    @Schema(description = "处置动作中文名")
    private String dispositionName;

    @Schema(description = "处置说明")
    private String handleRemark;

    @Schema(description = "举报人昵称，查不到为「未知用户」")
    private String reporterName;

    @Schema(description = "举报人头像")
    private String reporterAvatar;

    @Schema(description = "被举报人 ID，反查不到为空")
    private Long reportedUserId;

    @Schema(description = "被举报人昵称")
    private String reportedUserName;

    @Schema(description = "被举报人账号状态：1 正常 0 已禁用；账号已注销为空")
    private Integer reportedUserStatus;

    @Schema(description = "该被举报人当前还有几张未处置工单（含本张），用于判断是不是惯犯")
    private Long reportedPendingCount;

    @Schema(description = "证据快照（提交那一刻抓的），对象后来被删也不影响处置")
    private ReportTargetDTO evidence;

    @Schema(description = "本张工单可执行的处置动作码")
    private List<String> allowedDispositions;

    @Schema(description = "处理人昵称，未处理为空")
    private String handlerName;

    @Schema(description = "举报时间")
    private LocalDateTime createTime;

    @Schema(description = "处理时间")
    private LocalDateTime handleTime;
}
