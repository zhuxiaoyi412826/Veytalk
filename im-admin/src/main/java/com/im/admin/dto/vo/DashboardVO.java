package com.im.admin.dto.vo;

import com.im.common.domain.AdminMetricDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台看板。
 *
 * <p>三块内容按「数据来源」而不是按「看起来像不像一屏」划分：
 * {@code sections} 是各业务模块报上来的平台数字（本模块一张表都不碰），
 * {@code report*} 是举报工单自己的统计（趋势、理由分布、重点关注对象），
 * {@code cachedAt}/{@code staleSeconds} 则把「这屏数字最多滞后多久」明写出来。
 *
 * <p>把滞后信息放进响应而不是只写在配置里，是因为前端拿到数字之后要显示「数据截至 …」，
 * 让管理员自己去猜缓存 TTL 等于让每个看板都变成一个可能过期的谎言。
 */
@Data
@Schema(description = "后台看板")
public class DashboardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "各模块贡献的平台数字，按分区组织；模块未装配就没有那个分区")
    private List<Section> sections;

    @Schema(description = "待处理工单数（状态 0 与 1）")
    private Long pendingReports;

    @Schema(description = "今日新增举报数")
    private Long todayReports;

    @Schema(description = "近 7 天举报趋势，缺数据的日期补 0")
    private List<TrendPoint> trend;

    @Schema(description = "近 7 天举报理由分布，按条数倒序")
    private List<ReasonSlice> reasons;

    @Schema(description = "近 7 天被举报次数最多的用户，最多 10 位")
    private List<TopUser> topReportedUsers;

    @Schema(description = "本屏数字的生成时刻")
    private LocalDateTime cachedAt;

    @Schema(description = "缓存时长（秒），0 表示未缓存")
    private Integer staleSeconds;

    /** 一个业务模块在看板上的分区 */
    @Data
    @Schema(description = "看板分区")
    public static class Section implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "分区标识（英文小写）")
        private String source;

        @Schema(description = "分区标题（中文）")
        private String title;

        @Schema(description = "分区内的指标")
        private List<AdminMetricDTO> metrics;
    }

    /** 趋势上的一个自然日 */
    @Data
    @Schema(description = "举报趋势上的一个点")
    public static class TrendPoint implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "日期")
        private LocalDate day;

        @Schema(description = "当日举报数")
        private Long count;
    }

    /** 理由分布里的一档 */
    @Data
    @Schema(description = "举报理由分布")
    public static class ReasonSlice implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "理由码")
        private String code;

        @Schema(description = "理由中文名")
        private String name;

        @Schema(description = "条数")
        private Long count;
    }

    /** 被举报榜首的一位用户 */
    @Data
    @Schema(description = "被举报次数榜的一位用户")
    public static class TopUser implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "用户 ID")
        private Long userId;

        @Schema(description = "昵称，查不到为「未知用户」")
        private String nickname;

        @Schema(description = "头像")
        private String avatar;

        @Schema(description = "账号状态：1 正常 0 已禁用；已注销为空")
        private Integer status;

        @Schema(description = "窗口内被举报次数")
        private Long count;
    }
}
