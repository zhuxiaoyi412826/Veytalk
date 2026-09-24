package com.im.remote.agent;

import javax.swing.SwingUtilities;

/**
 * 被控端入口：`java -jar im-remote-agent.jar`。
 *
 * <p>Robot 截屏与 Swing 弹窗都要求真实的桌面会话——无头环境（服务账号、
 * ssh）下直接报错退出，而不是默默提供一个「看起来在线但什么都做不了」的 Agent。
 */
public final class AgentApplication {

    private AgentApplication() {
    }

    public static void main(String[] args) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println("错误：被控端需要桌面会话（截屏/弹窗授权），请勿在无头环境运行");
            System.exit(2);
        }
        AgentConfig config = new AgentConfig();
        SwingUtilities.invokeLater(() -> {
            AgentUI ui = new AgentUI(config);
            ui.setVisible(true);
            // 回环识别码接口：本机浏览器页面靠它读「本机识别码」，起不来也不影响主流程
            LocalInfoServer localInfo = LocalInfoServer.start(config, ui::isClientOnline);
            if (localInfo != null) {
                Runtime.getRuntime().addShutdownHook(new Thread(localInfo::stop, "local-info-stop"));
            }
            // 账号或识别码任一就绪即自动连接，满足「装好即常驻」的期望
            if (!config.username().isEmpty() && !config.password().isEmpty() || !config.accessCode().isEmpty()) {
                ui.autoConnect();
            }
        });
    }
}
