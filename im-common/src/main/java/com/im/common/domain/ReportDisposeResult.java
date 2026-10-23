package com.im.common.domain;

import lombok.Getter;

/**
 * 处置动作的执行结果，由 {@code ReportTargetSpi} 返回给管理后台。
 *
 * <p>不用 {@code boolean} 而带一句 {@link #message}：处置失败是要<b>给管理员看见</b>的——
 * 「消息不存在或已被撤回」「房间已经关播」和「你无权执行该操作」是三种完全不同的现场，
 * 只回一个 false 的话，管理员只能反复点同一个按钮。
 *
 * <p>成功时 {@link #message} 可带一句补充说明（例如「已中断直播并断流」），
 * 后台把它显示在处置结果里；为空则显示动作本身的成功文案。
 *
 * <p>只有两个静态工厂，是为了让「失败必须带原因」这条约束由编译器保证：
 * 没有第三个人能写出不带 message 的失败。
 */
@Getter
public class ReportDisposeResult {

    private final boolean success;
    private final String message;

    private ReportDisposeResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static ReportDisposeResult ok() {
        return new ReportDisposeResult(true, null);
    }

    public static ReportDisposeResult ok(String message) {
        return new ReportDisposeResult(true, message);
    }

    /**
     * 执行失败。
     *
     * @param message 失败原因，会原样显示给管理员，所以写给「人」看，不要抛异常堆栈
     */
    public static ReportDisposeResult fail(String message) {
        return new ReportDisposeResult(false, message == null || message.isBlank() ? "处置失败" : message);
    }
}
