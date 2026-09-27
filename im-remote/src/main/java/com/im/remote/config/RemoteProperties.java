package com.im.remote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 远程控制模块配置，挂在 {@code im.remote} 前缀下。
 *
 * <p>中继带宽是这个功能最容易被误配的地方：一屏增量 JPEG 约几十 KB、20fps 就是
 * 单会话 1MB/s 的上行，所以帧大小与空闲超时都必须有服务端硬限制，
 * 不能指望两端客户端自觉。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im.remote")
public class RemoteProperties {

    /** 总开关：关闭后 REST 与两个 WS 端点都拒绝服务，Agent 连接会被直接关闭 */
    private boolean enabled = true;

    /**
     * 是否启用屏幕/文件载荷的 AES-GCM 端到端加密。
     *
     * <p>关掉仅用于内网演示排障；中继服务器设计上只路由不解密，
     * 开着时它连画面内容都看不到，关掉则链路抓包即可还原屏幕。
     */
    private boolean aes = true;

    /** 会话空闲多少分钟后服务端主动断开（两端都没有任何帧往来） */
    private int idleTimeoutMinutes = 5;

    /** 邀请发出后被控端多少秒内未授权则自动作废 */
    private int inviteTimeoutSeconds = 60;

    /** 单个二进制帧的字节上限，超过即关闭连接（防恶意超大帧撑爆内存） */
    private int maxFrameBytes = 1024 * 1024;

    /** 单条文本帧的字节上限，cmd 回显、文件列表 JSON 都走文本帧 */
    private int maxTextBytes = 256 * 1024;

    /** Agent 心跳间隔建议值（秒），随 ready 帧下发；服务端按 3 倍判离线 */
    private int heartbeatIntervalSeconds = 30;

    /** 一次性控制端连接票据有效期（秒） */
    private long controlTicketTtlSeconds = 60;

    /**
     * 直连（P2P）配置：控制端与被控端之间绕过中继的第三条通路。
     *
     * <p>默认全关是有意的：一旦开启，被控机就会常开两个监听端口（TCP + UDP），
     * 这是整套远程能力里唯一「往内网开口子」的变化，必须由部署方显式决定。
     * 关闭时中继行为与引入直连之前逐字节一致。
     */
    private Direct direct = new Direct();

    @Data
    public static class Direct {

        /** 服务端侧总开关：关闭后不下发直连票据、不透传候选帧，两端只会走中继 */
        private boolean enabled = false;

        /** 局域网直连（TCP + 原生 WebSocket）：同网段时零配置可用，不需要打洞 */
        private boolean lanEnabled = true;

        /**
         * UDP 打洞（仅 Electron 桌面端可用：浏览器开不了原始 UDP）。
         * 依赖 punch-host 可达，配不到公网地址时自动退化为「只做局域网直连」。
         */
        private boolean udpEnabled = true;

        /**
         * 打洞地址反射服务对外可达的地址（公网 IP 或域名）。
         *
         * <p>NAT 后的两端看不到自己对外暴露的 ip:port，必须问服务端「我看起来是谁」。
         * 留空表示不发布该能力，UDP 直连整体禁用——它没法由服务端猜，因为部署环境
         * 究竟是直连公网还是挂在反向代理后面，只有部署方知道。
         */
        private String punchHost = "";

        /** 打洞地址反射服务监听的 UDP 端口（与 HTTP 端口无关，需在防火墙放行 UDP 入站） */
        private int punchPort = 8947;

        /**
         * 反射服务对单个来源地址的问答次数上限（滑动 60 秒窗口）。
         * 这个接口无状态、无鉴权，不限流就会被当成 UDP 放大/反射探测的免费跳板。
         */
        private int punchRateLimit = 20;

        /** 直连票据有效期（秒）：只覆盖「建会话到直连握手完成」这段窗口，不做长期凭证 */
        private long tokenTtlSeconds = 60;

        /** 单个 DXP 报文的字节上限（UDP 分片尺寸，留足 IP/UDP 头以避开分片） */
        private int mtu = 1200;

        /** 直连握手尝试预算（毫秒）：超时即判该档不可用，落回下一档 */
        private long handshakeTimeoutMs = 3000;
    }
}
