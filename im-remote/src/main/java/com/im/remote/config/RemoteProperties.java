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
}
