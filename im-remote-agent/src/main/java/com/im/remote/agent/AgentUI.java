package com.im.remote.agent;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

/**
 * 被控端主窗口：服务器/账号/识别码配置、连接控制、安全开关与操作日志。
 *
 * <p>账号密码可留空——此时 Agent 以识别码模式匿名接入，控制方凭识别码
 * 发起邀请（仍需弹窗同意）；填了账号则同时归属到该账号的「我的设备」。
 *
 * <p>「拒绝接入」与「允许高危操作」刻意做成运行期可改的复选框并即时落盘：
 * 被控方随时可以一键拒绝一切新连接，而不必先关程序改文件再重启。
 */
public class AgentUI extends JFrame implements AgentClient.AgentUi {

    private final AgentConfig config;
    private final JTextField serverField;
    private final JTextField accountField;
    private final JPasswordField passwordField;
    private final JTextField deviceNameField;
    private final JTextField accessCodeField;
    private final JCheckBox refuseBox;
    private final JCheckBox dangerBox;
    private final javax.swing.JTextArea logArea;
    private final JLabel statusLabel;
    private volatile AgentClient client;
    private volatile Thread clientThread;
    private volatile AlertUI.AlertBar alertBar;

    public AgentUI(AgentConfig config) {
        this.config = config;
        setTitle("IM 远程控制被控端 - " + config.deviceName());
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        serverField = new JTextField(config.serverUrl());
        accountField = new JTextField(config.username());
        passwordField = new JPasswordField(config.password());
        deviceNameField = new JTextField(config.deviceName());
        accessCodeField = new JTextField(config.accessCode());
        JButton randomCodeButton = new JButton("随机");
        randomCodeButton.setMargin(new java.awt.Insets(2, 8, 2, 8));
        randomCodeButton.addActionListener(e -> accessCodeField.setText(AgentConfig.randomCode()));
        JPanel codeRow = fieldRow("识别码", accessCodeField);
        codeRow.add(randomCodeButton, BorderLayout.EAST);
        form.add(fieldRow("服务器地址", serverField));
        form.add(fieldRow("设备名称", deviceNameField));
        form.add(codeRow);
        form.add(fieldRow("账号(可选)", accountField));
        form.add(fieldRow("密码(可选)", passwordField));

        refuseBox = new JCheckBox("拒绝一切远程接入", config.refuse());
        dangerBox = new JCheckBox("允许高危操作（删除文件 / 结束进程 / cmd / 电源）", config.allowDanger());
        refuseBox.addActionListener(e -> config.setRefuse(refuseBox.isSelected()));
        dangerBox.addActionListener(e -> config.setAllowDanger(dangerBox.isSelected()));
        form.add(refuseBox);
        form.add(dangerBox);

        JButton connectButton = new JButton("保存并连接");
        JButton disconnectButton = new JButton("断开");
        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> {
            if (!saveConfig()) {
                return;
            }
            startClient();
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
        });
        disconnectButton.addActionListener(e -> {
            stopClient();
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        });
        JPanel buttons = new JPanel();
        buttons.add(connectButton);
        buttons.add(disconnectButton);
        form.add(buttons);

        statusLabel = new JLabel("识别码: " + config.accessCode() + "　设备ID: " + config.deviceId() + "　状态: 未连接");
        form.add(statusLabel);
        add(form, BorderLayout.NORTH);

        logArea = new javax.swing.JTextArea(12, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(520, 240));
        add(scroll, BorderLayout.CENTER);

        setJMenuBar(buildMenuBar());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopClient();
                dispose();
                System.exit(0);
            }
        });
        pack();
        setLocationByPlatform(true);
    }

    private javax.swing.JMenuBar buildMenuBar() {
        javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
        javax.swing.JMenu fileMenu = new javax.swing.JMenu("文件");
        javax.swing.JMenuItem openConfig = new javax.swing.JMenuItem("打开配置目录");
        openConfig.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().open(new java.io.File(System.getProperty("user.dir")));
            } catch (Exception ignored) {
                // 无桌面环境的机器打不开目录，忽略即可
            }
        });
        javax.swing.JMenuItem exit = new javax.swing.JMenuItem("退出");
        exit.addActionListener(e -> {
            stopClient();
            System.exit(0);
        });
        fileMenu.add(openConfig);
        fileMenu.add(exit);
        menuBar.add(fileMenu);
        return menuBar;
    }

    private JPanel fieldRow(String label, java.awt.Component field) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        JLabel name = new JLabel(label);
        name.setPreferredSize(new Dimension(80, 24));
        row.add(name, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return row;
    }

    private boolean saveConfig() {
        String code = accessCodeField.getText().trim().toUpperCase();
        if (!code.matches("[A-Z0-9]{6,12}")) {
            javax.swing.JOptionPane.showMessageDialog(this, "识别码需为 6-12 位字母或数字", "配置不合法",
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            accessCodeField.requestFocusInWindow();
            return false;
        }
        accessCodeField.setText(code);
        config.set("server.url", serverField.getText().trim());
        config.set("device.name", deviceNameField.getText().trim());
        config.set("account.username", accountField.getText().trim());
        config.set("account.password", new String(passwordField.getPassword()));
        config.save();
        config.setAccessCode(code);
        return true;
    }

    /** 启动时账号或识别码已就绪则免点按钮直接连 */
    public void autoConnect() {
        startClient();
    }

    /** 当前客户端是否已注册到中继，供本机识别码接口上报 */
    public boolean isClientOnline() {
        AgentClient current = client;
        return current != null && current.isOnline();
    }

    private synchronized void startClient() {
        if (clientThread != null && clientThread.isAlive()) {
            return;
        }
        try {
            client = new AgentClient(config, this);
        } catch (Exception e) {
            // 构造失败（如裁剪 JRE 缺加密模块导致 SSLContext 初始化失败）也要保住 UI：
            // 异常若飞出 Swing 线程就是静默死，现场再也看不到原因
            client = null;
            log("客户端启动失败: " + e);
            statusLabel.setText("识别码: " + config.accessCode() + "　设备ID: " + config.deviceId() + "　状态: 启动失败");
            return;
        }
        AgentClient current = client;
        clientThread = new Thread(current::runLoop, "agent-client");
        clientThread.setDaemon(true);
        clientThread.start();
        statusLabel.setText("识别码: " + config.accessCode() + "　设备ID: " + config.deviceId() + "　状态: 连接中…");
    }

    private synchronized void stopClient() {
        AgentClient current = client;
        if (current != null) {
            current.stop();
        }
        client = null;
        clientThread = null;
        statusLabel.setText("识别码: " + config.accessCode() + "　设备ID: " + config.deviceId() + "　状态: 已断开");
    }

    /* ==================== AgentClient.AgentUi ==================== */

    @Override
    public void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
            if (message.contains("设备已注册")) {
                statusLabel.setText("识别码: " + config.accessCode() + "　设备ID: " + config.deviceId() + "　状态: 在线");
            }
        });
    }

    @Override
    public AlertUI.Invite askInvite(String nickname, String requestedPermission) {
        return AlertUI.ask(null, nickname, requestedPermission);
    }

    @Override
    public void onSessionStarted(String nickname, String permission) {
        statusLabel.setText("设备ID: " + config.deviceId() + "　状态: 正在被 " + nickname + " "
                + ("operate".equals(permission) ? "操作" : "查看"));
        AlertUI.AlertBar bar = new AlertUI.AlertBar(nickname, () -> {
            AgentClient current = client;
            if (current != null) {
                current.endSessionByAgent();
            }
        });
        alertBar = bar;
        bar.show();
    }

    @Override
    public void onSessionEnded(String reason) {
        if (alertBar != null) {
            alertBar.hide();
            alertBar = null;
        }
        statusLabel.setText("设备ID: " + config.deviceId() + "　状态: 在线（会话已结束: " + reason + "）");
    }
}
