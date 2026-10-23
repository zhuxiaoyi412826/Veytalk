package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报工单状态，写进 {@code im_report.status}。
 *
 * <p>四个状态里只有 {@link #PENDING} 会被用户制造出来，其余三个都是管理员的动作结果。
 * 之所以要有 {@link #ACCEPTED} 这个中间态：管理员点了「受理」表示「这单我看了、正在查」，
 * 举报人此时能看到进展，而不是从「待处理」直接跳到最终结论；
 * 但也因此它是<b>纯人工状态</b>——没有任何超时任务会自动推进它，没人受理就一直是待处理。
 *
 * <p>终态（{@link #REJECTED} / {@link #HANDLED}）后不允许再改：见 {@code REPORT_ALREADY_HANDLED}。
 * 判错了就另开一张工单，不改历史工单，否则「谁在什么时候以什么理由处置过」这条追责线就断了。
 */
@Getter
public enum ReportStatus {

    /** 待处理 */
    PENDING(0, "待处理"),
    /** 已受理：管理员认领，尚未给出处置 */
    ACCEPTED(1, "已受理"),
    /** 已驳回：经核实不成立 */
    REJECTED(2, "已驳回"),
    /** 已处置：执行了某个处置动作 */
    HANDLED(3, "已处置");

    @JsonValue
    private final int code;
    private final String desc;

    ReportStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ReportStatus of(Integer code) {
        if (code != null) {
            for (ReportStatus value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 是否为终态：终态工单不允许再处置。
     */
    public boolean finished() {
        return this == REJECTED || this == HANDLED;
    }

    /**
     * 状态码是否为终态，供只拿到数字的场合（如从库里读出的 status）直接判定。
     */
    public static boolean isFinished(Integer code) {
        ReportStatus value = of(code);
        return value != null && value.finished();
    }

    /**
     * 按状态码取中文描述，用于列表回显。
     */
    public static String descOf(Integer code) {
        ReportStatus value = of(code);
        return value == null ? "" : value.desc;
    }
}
