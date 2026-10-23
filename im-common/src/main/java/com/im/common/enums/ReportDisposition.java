package com.im.common.enums;

import lombok.Getter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 管理员对举报工单执行的处置动作，写进 {@code im_report.disposition}。
 *
 * <p>与 {@link ReportReason} 相反，这里<b>必须</b>声明适用范围：
 * 「封禁账号」对一条消息毫无意义，「撤回消息」也不能作用在一个群上。
 * 后台下拉里只会出现当前工单的对象类型支持的几个动作，服务端再校验一次
 * （前端传什么都能改，越界的组合一旦落库就成了没人能复核的脏数据）。
 *
 * <p><b>执行者的分界</b>：{@link #NONE} 与 {@link #WARN} 只改工单状态、给客户发一条通知，
 * 由管理后台自己完成；其余三个都要<b>动对象本身</b>（改用户状态、撤回消息、中断直播），
 * 只能由归属模块执行，后台经 {@code ReportTargetSpi} 调用——星型架构下 im-admin 不依赖任何业务模块。
 * 归属模块缺席时（比如没打 im-live），执行 {@link #CLOSE_ROOM} 会返回
 * {@code ADMIN_DISPOSITION_UNAVAILABLE} 而不是静默成功。
 */
@Getter
public enum ReportDisposition {

    /**
     * 确认但不采取行动。
     *
     * <p>与「已驳回」的区别要说清：驳回是判定<b>举报不成立</b>，
     * {@code none} 是事实确实如此但不足以动手（比如内容确实吵但没违规、或证据不足但倾向可信）。
     * 两者都必须给举报人一个结论，只不外显为「处罚」——没这个区分时，
     * 管理员只能在「驳回」里把「看了但不管」一并塞进去，举报人看到的就是一句假的「不实」。
     */
    NONE("none", "不处理", EnumSet.allOf(ReportTargetType.class), false),
    /** 警告当事人：给被举报人推一条系统通知，不动账号状态 */
    WARN("warn", "警告当事人", EnumSet.allOf(ReportTargetType.class), true),
    /** 封禁账号：置 {@code im_user.status = 0} 并踢下线 */
    BAN_USER("ban_user", "封禁账号", EnumSet.of(ReportTargetType.USER), false),
    /** 解除封禁：留给「举报不成立但人已被封」的纠偏，也用于误封 */
    UNBAN_USER("unban_user", "解除封禁", EnumSet.of(ReportTargetType.USER), false),
    /** 撤回消息：按消息自己的撤回链路走，会话内各端同步显示「已撤回」 */
    RECALL_MESSAGE("recall_message", "撤回消息", EnumSet.of(ReportTargetType.MESSAGE), false),
    /** 中断直播：关房间并断推流，结束原因记为 ban */
    CLOSE_ROOM("close_room", "中断直播", EnumSet.of(ReportTargetType.ROOM), false);

    /** 落库的码串 */
    private final String code;
    private final String desc;
    /** 能作用在哪些对象类型上 */
    private final Set<ReportTargetType> appliesTo;
    /** 是否需要工单上有被举报人（WARN 得知道警告谁） */
    private final boolean requiresOwner;

    ReportDisposition(String code, String desc, Set<ReportTargetType> appliesTo, boolean requiresOwner) {
        this.code = code;
        this.desc = desc;
        this.appliesTo = appliesTo;
        this.requiresOwner = requiresOwner;
    }

    public static ReportDisposition of(String code) {
        if (code != null) {
            for (ReportDisposition value : values()) {
                if (value.code.equalsIgnoreCase(code.trim())) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 该动作能否用在这种对象上。
     */
    public boolean supports(ReportTargetType type) {
        return type != null && appliesTo.contains(type);
    }

    /**
     * 该动作是否由管理后台自己完成（不需要找归属模块）。
     */
    public boolean local() {
        return this == NONE || this == WARN;
    }

    /**
     * 取某类对象可用的动作码串列表，供前端渲染处置下拉。
     */
    public static List<String> codesFor(ReportTargetType type) {
        List<String> codes = new ArrayList<>();
        for (ReportDisposition value : values()) {
            if (value.supports(type)) {
                codes.add(value.code);
            }
        }
        return codes;
    }
}
