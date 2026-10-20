package com.im.admin.dto.po;

import lombok.Data;

import java.time.LocalDate;

/**
 * 举报表的聚合行，只为看板与「谁被投诉最多」这几条 GROUP BY 服务。
 *
 * <p>与 {@code im-live} 的 {@code GiftAggPO} 同一个套路：聚合结果的形状跟实体表无关，
 * 硬塞进 {@code Report} 实体会让 MyBatis-Plus 的通用 CRUD 误以为这些列真的存在。
 * 外层不可实例化（私有构造），只当返回值的容器。
 */
public final class ReportAggPO {

    private ReportAggPO() {
    }

    /** 按自然日的一条计数，用于看板的举报趋势 */
    @Data
    public static class Day {
        /** 日期，SQL 里由 {@code DATE(create_time)} 得出 */
        private LocalDate day;
        /** 当日举报条数 */
        private Long count;
    }

    /** 按理由码的一条计数，用于看板的理由分布 */
    @Data
    public static class Reason {
        /** 理由码，对应 {@code ReportReason#getCode()} */
        private String reasonCode;
        /** 该理由的条数 */
        private Long count;
    }

    /** 按被举报人的一条计数，用于「重点关注对象」榜单与用户列表的被举报次数列 */
    @Data
    public static class UserCount {
        /** 被举报人 ID */
        private Long userId;
        /** 被举报条数 */
        private Long count;
    }
}
