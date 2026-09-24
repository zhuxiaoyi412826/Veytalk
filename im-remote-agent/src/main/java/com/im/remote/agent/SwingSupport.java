package com.im.remote.agent;

import javax.swing.SwingUtilities;
import java.util.function.Supplier;

/**
 * EDT 调度小工具：Robot/弹窗/悬浮条都必须在 Swing 事件线程操作，
 * 而帧分发发生在 WS 回调线程——这里收口两种模式：
 * fire-and-forget（onEdt）与「在 EDT 上取值」（onEdtResult，异步不阻塞回调线程，
 * 邀请弹窗因此不会冻住心跳）。
 */
final class SwingSupport {

    private SwingSupport() {
    }

    static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    static <T> void onEdtResult(Supplier<T> supplier) {
        SwingUtilities.invokeLater(() -> {
            try {
                supplier.get();
            } catch (Exception e) {
                System.err.println("EDT 任务失败: " + e.getMessage());
            }
        });
    }
}
