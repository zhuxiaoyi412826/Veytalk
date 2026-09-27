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

    /* ==================== 直连（P2P）与硬件编码 ==================== */

    /**
     * 被控端是否允许开直连监听口。默认 false 是刻意的：开了就有两个常开端口，
     * 这是整套被控能力里唯一「往外网开口子」的变化，必须由使用者显式勾选；
     * 且服务端 {@code im.remote.direct.enabled} 也为真时才真正生效（两道门）。
     */
    public boolean directEnabled() {
        return Boolean.parseBoolean(get("direct.enabled", "false"));
    }

    public void setDirectEnabled(boolean value) {
        set("direct.enabled", String.valueOf(value));
        save();
    }

    /** 局域网直连端口（TCP，同一端口兼容 WebSocket 升级与裸帧） */
    public int directTcpPort() {
        return intOf("direct.tcpPort", 18924);
    }

    /** 打洞直连端口（UDP），必须与反射服务回显的公网映射端口一致才好复用同一 socket */
    public int directUdpPort() {
        return intOf("direct.udpPort", 18925);
    }

    /** 关掉后只接受 UDP 打洞直连，不监听局域网端口 */
    public boolean directAllowLan() {
        return Boolean.parseBoolean(get("direct.allowLan", "true"));
    }

    public void setDirectAllowLan(boolean value) {
        set("direct.allowLan", String.valueOf(value));
        save();
    }

    /** ffmpeg 可执行文件路径；留空按「jar 同级 ../ffmpeg/ffmpeg.exe → IM_FFMPEG_PATH → PATH」找 */
    public String ffmpegPath() {
        return get("ffmpeg.path", "");
    }

    /** 编码器：auto 按 nvenc → qsv → amf → libx264 顺序探测 */
    public String ffmpegEncoder() {
        String value = get("ffmpeg.encoder", "auto");
        return value.isEmpty() ? "auto" : value;
    }

    /** 是否允许 screen-start 请求 h264；关掉表示无论控制端要什么都只发 JPEG */
    public boolean h264Enabled() {
        return Boolean.parseBoolean(get("h264.enabled", "true"));
    }

    public void setH264Enabled(boolean value) {
        set("h264.enabled", String.valueOf(value));
        save();
    }

    /** H.264 目标码率（bps），屏幕推流默认 2.5Mbps 已够 1280 宽的办公画面 */
    public int h264Bitrate() {
        return intOf("h264.bitrate", 2_500_000);
    }

    private int intOf(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
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
