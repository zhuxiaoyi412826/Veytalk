package com.im.remote.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * 被控端配置：读写工作目录下的 config.properties。
 *
 * <p>deviceId 首次启动生成并持久化——它同时是服务端识别「这台机器」的稳定
 * 身份（用户账号下多台设备靠它区分），重新生成会让用户看到一堆幽灵设备。
 *
 * <p>密码明文存在本地是演示项目的刻意取舍：Agent 需要无人值守自启动重连，
 * 任何需要交互的凭证方案（OAuth/扫码）都超出本期范围；文档中已注明
 * 「config.properties 含账号密码，请存放在受控目录」。
 */
public class AgentConfig {

    private static final String FILE_NAME = "config.properties";

    private final Path file;
    private final Properties props = new Properties();

    public AgentConfig() {
        this.file = Path.of(System.getProperty("user.dir"), FILE_NAME);
        load();
    }

    private void load() {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("读取 config.properties 失败: " + e.getMessage());
            }
        }
        if (deviceId().isEmpty()) {
            set("deviceId", UUID.randomUUID().toString().replace("-", ""));
            save();
        }
    }

    public void save() {
        try (OutputStream out = Files.newOutputStream(file)   ;
             java.io.Writer writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            props.store(writer, "im-remote-agent config");
        } catch (IOException e) {
            System.err.println("写入 config.properties 失败: " + e.getMessage());
        }
    }

    private String get(String key, String def) {
        return props.getProperty(key, def).trim();
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }

    /** 服务器 HTTP 基址，如 http://127.0.0.1:8080 */
    public String serverUrl() {
        String url = get("server.url", "http://127.0.0.1:8080");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** WS 基址：默认由 server.url 推导（http->ws / https->wss），可用 server.ws 显式覆盖 */
    public String wsBase() {
        String override = get("server.ws", "");
        if (!override.isEmpty()) {
            return override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
        }
        String url = serverUrl();
        return url.startsWith("https") ? "wss" + url.substring(5) : "ws" + url.substring(4);
    }

    public String username() {
        return get("account.username", "");
    }

    public String password() {
        return get("account.password", "");
    }

    public String deviceId() {
        return get("deviceId", "");
    }

    /**
     * 识别码（ToDesk 式跨账号接入凭证）：首次使用自动生成 6 位大写字母数字并持久化。
     *
     * <p>免账号模式下它是这台 Agent 的唯一身份——控制方凭它路由到本机；
     * 格式与服务端 auth 帧校验、invite-by-code 入参正则 [A-Z0-9]{6,12} 保持一致。
     */
    public String accessCode() {
        String code = get("accessCode", "");
        if (code.isEmpty()) {
            code = randomCode();
            set("accessCode", code);
            save();
        }
        return code;
    }

    public void setAccessCode(String code) {
        set("accessCode", code == null ? "" : code.trim().toUpperCase());
        save();
    }

    /** 生成 6 位识别码，字符集去掉易混淆的 I、O、0、1 */
    public static String randomCode() {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder builder = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            builder.append(chars.charAt(random.nextInt(chars.length())));
        }
        return builder.toString();
    }

    /** 设备展示名，默认取机器名 */
    public String deviceName() {
        String name = get("device.name", "");
        if (!name.isEmpty()) {
            return name;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Agent-" + System.getProperty("os.name", "unknown");
        }
    }

    /**
     * 允许远程访问的目录根（分号分隔）。默认放通本机所有可读盘——
     * 这是演示项目的开箱即用取舍，生产部署应显式收窄。
     */
    public String allowRoots() {
        return get("file.allowRoots", defaultRoots());
    }

    private static String defaultRoots() {
        StringBuilder builder = new StringBuilder();
        for (Path root : FileRoots.list()) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(root);
        }
        return builder.length() == 0 ? "C:\\" : builder.toString();
    }

    public boolean allowDanger() {
        return Boolean.parseBoolean(get("security.allowDanger", "false"));
    }

    /** 本机识别码只读接口端口（仅绑 127.0.0.1），被占时该接口静默不启 */
    public int localInfoPort() {
        try {
            return Integer.parseInt(get("local.infoPort", "18923"));
        } catch (NumberFormatException e) {
            return 18923;
        }
    }

    public void setAllowDanger(boolean value) {
        set("security.allowDanger", String.valueOf(value));
        save();
    }

    public boolean refuse() {
        return Boolean.parseBoolean(get("security.refuse", "false"));
    }

    public void setRefuse(boolean value) {
        set("security.refuse", String.valueOf(value));
        save();
    }

    /** 本机所有盘符根，Windows 形如 [C:\, D:\]，Linux/macOS 返回 [/] */
    static final class FileRoots {
        static java.util.List<Path> list() {
            java.util.List<Path> roots = new java.util.ArrayList<>();
            for (java.io.File root : java.io.File.listRoots()) {
                roots.add(root.toPath());
            }
            return roots;
        }
    }
}
