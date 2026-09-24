package com.im.remote.agent;

import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Map;

/**
 * 键鼠注入：归一化坐标（0~10000）→ 本端像素坐标 → Robot 执行。
 *
 * <p>控制端传来的是归一化值而不是像素：两端分辨率、缩放、显示器布局
 * 全都可能不同，归一化让「1080p 控制端点屏幕正中」精确落在被控端
 * 当前推流显示器的正中间。组合键不需要特殊处理——控制端按什么
 * press/release 序列，这里就原样驱动 Robot。
 *
 * <p>只读模式在语义上属于授权，由服务端中继拦截；这里再挡一道
 * （permission 字段随 session-start 同步过来），防的是直连 Agent 的伪造帧。
 */
public class InputHandler {

    private static final int NORMAL_MAX = 10000;

    private final AgentClient client;
    private final ScreenCapturer capturer;
    private volatile Robot robot;

    public InputHandler(AgentClient client, ScreenCapturer capturer) {
        this.client = client;
        this.capturer = capturer;
    }

    private Robot robot() throws Exception {
        if (robot == null) {
            robot = new Robot();
            robot.setAutoDelay(0);
        }
        return robot;
    }

    public void handleMouse(Map<String, Object> data) throws Exception {
        if (!client.allowInput()) {
            client.sendLocalError("READONLY", "只读会话不接受输入");
            return;
        }
        String action = MiniJson.str(data, "action");
        Rectangle bounds = capturer.currentBounds();
        int x = bounds.x + (int) (MiniJson.lng(data, "x", 0) / (double) NORMAL_MAX * bounds.width);
        int y = bounds.y + (int) (MiniJson.lng(data, "y", 0) / (double) NORMAL_MAX * bounds.height);
        int button = switch (MiniJson.str(data, "button") == null ? "left" : MiniJson.str(data, "button")) {
            case "right" -> MouseEvent.BUTTON3;
            case "middle" -> MouseEvent.BUTTON2;
            default -> MouseEvent.BUTTON1;
        };
        Robot r = robot();
        switch (action == null ? "" : action) {
            case "move" -> r.mouseMove(x, y);
            case "down" -> {
                r.mouseMove(x, y);
                r.mousePress(toMask(button));
            }
            case "up" -> {
                r.mouseMove(x, y);
                r.mouseRelease(toMask(button));
            }
            case "click" -> {
                r.mouseMove(x, y);
                r.mousePress(toMask(button));
                r.delay(40);
                r.mouseRelease(toMask(button));
            }
            case "wheel" -> r.mouseWheel((int) MiniJson.lng(data, "deltaY", 0) / 100);
            default -> client.log("未知鼠标动作: " + action);
        }
    }

    private int toMask(int button) {
        return switch (button) {
            case MouseEvent.BUTTON2 -> InputEvent.getMaskForButton(2);
            case MouseEvent.BUTTON3 -> InputEvent.getMaskForButton(3);
            default -> InputEvent.getMaskForButton(1);
        };
    }

    /** data: {action: press|release, keyCode: Swing KeyEvent 常量} */
    public void handleKey(Map<String, Object> data) throws Exception {
        if (!client.allowInput()) {
            client.sendLocalError("READONLY", "只读会话不接受输入");
            return;
        }
        String action = MiniJson.str(data, "action");
        int keyCode = (int) MiniJson.lng(data, "keyCode", 0);
        if (keyCode <= 0) {
            return;
        }
        Robot r = robot();
        if ("press".equals(action)) {
            r.keyPress(keyCode);
        } else if ("release".equals(action)) {
            r.keyRelease(keyCode);
        }
    }
}
