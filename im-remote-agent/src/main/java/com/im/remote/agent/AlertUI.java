package com.im.remote.agent;

import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 被控方授权 UI：邀请确认弹窗 + 会话期悬浮告警条。
 *
 * <p>「必须被控方同意才能远程」是整个授权模型的地基：弹窗在被控端本地
 * 渲染，服务端与控制端都无法伪造或跳过；权限下拉允许降档为只读——
 * 升档在协议层就不存在。
 */
public final class AlertUI {

    private AlertUI() {
    }

    /** 邀请决定：accept + 实际授予的权限 */
    public record Invite(boolean accept, String permission) {
    }

    /**
     * 模态询问是否接受远程邀请。必须在 EDT 上调用（AgentClient 已保证）。
     */
    public static Invite ask(java.awt.Component parent, String nickname, String requestedPermission) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("「" + nickname + "」请求远程控制本机");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel hint = new JLabel("请确认对方身份；选择只读时对方的键盘鼠标操作会被拒绝。");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        String[] options = {"可操作（完整键盘鼠标控制）", "只读（仅查看屏幕）"};
        JComboBoxHolder holder = new JComboBoxHolder(options,
                "operate".equals(requestedPermission) ? 0 : 1);
        holder.box.setAlignmentX(Component.LEFT_ALIGNMENT);

        panel.add(title);
        panel.add(Box.createVerticalStrut(6));
        panel.add(hint);
        panel.add(Box.createVerticalStrut(6));
        panel.add(holder.box);

        int choice = JOptionPane.showConfirmDialog(parent, panel, "远程控制授权",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return new Invite(false, null);
        }
        return new Invite(true, holder.box.getSelectedIndex() == 0 ? "operate" : "readonly");
    }

    /** 泛型 JComboBox 局部变量写起来啰嗦，收进一个小 holder */
    private static final class JComboBoxHolder {
        final javax.swing.JComboBox<String> box;

        JComboBoxHolder(String[] options, int selected) {
            box = new javax.swing.JComboBox<>(options);
            box.setSelectedIndex(selected);
        }
    }

    /** 悬浮告警条：置顶无边框横条，点击即结束会话 */
    public static final class AlertBar {
        private final JDialog dialog;
        private final AtomicBoolean closed = new AtomicBoolean();

        public AlertBar(String nickname, Runnable onEnd) {
            dialog = new JDialog((JDialog) null, false);
            dialog.setUndecorated(true);
            dialog.setAlwaysOnTop(true);
            dialog.setOpacity(0.92f);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            JLabel label = new JLabel("  正在被「" + nickname + "」远程控制 —— 点击此处立即结束");
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
            JButton end = new JButton("结束");
            end.setForeground(new Color(180, 30, 30));
            end.setFocusPainted(false);
            JLabel dot = new JLabel("●");
            dot.setForeground(Color.WHITE);

            JPanel content = new JPanel(new java.awt.BorderLayout(8, 0));
            content.setBackground(new Color(190, 60, 60));
            content.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
            content.add(dot, java.awt.BorderLayout.WEST);
            content.add(label, java.awt.BorderLayout.CENTER);
            content.add(end, java.awt.BorderLayout.EAST);
            MouseAdapter click = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onEnd.run();
                }
            };
            label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            label.addMouseListener(click);
            end.addActionListener(e -> onEnd.run());

            dialog.setContentPane(content);
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            dialog.pack();
            Dimension size = new Dimension(Math.max(dialog.getWidth(), 420), 30);
            dialog.setSize(size);
            dialog.setLocation(new Point(screen.x + (screen.width - size.width) / 2,
                    screen.y + Math.max(8, (int) (screen.height * 0.02))));
        }

        public void show() {
            dialog.setVisible(true);
        }

        public void hide() {
            if (closed.compareAndSet(false, true)) {
                dialog.setVisible(false);
                dialog.dispose();
            }
        }
    }
}
