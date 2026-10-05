package com.im.live.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 直播模块配置，挂在 {@code im.live} 前缀下。
 *
 * <p><b>本模块刻意不碰媒体字节</b>：画面由推流端 ffmpeg 切成 HLS 分片直接 PUT 到
 * Nginx（或对象存储），观众从 Nginx 拉流；Java 只负责房间状态、播放地址签名与弹幕通道。
 * 因此这里的配置全是「控制面」参数，没有任何码率/分辨率之类的媒体参数——
 * 那些写在推流端的 ffmpeg 命令行里（见 {@code electron/main.js} 的开播逻辑）。
 *
 * <p>两个 base-url 是部署方必须显式配的：它们取决于 Nginx 挂在哪个域名/端口、
 * location 前缀是什么，服务端猜不出来。留空时开播接口会直接报错而不是下发一个
 * 拼不出来的相对地址——那种错误要等到播放器 404 才暴露，排查成本高得多。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im.live")
public class LiveProperties {

    /** 总开关：关闭后开播/观看接口与弹幕 WS 端点全部拒绝服务 */
    private boolean enabled = true;

    /**
     * 播放基址：观众侧实际请求 m3u8 的 URL 前缀，含 Nginx 的 location 前缀。
     * 例：{@code https://live.example.com/live}，最终地址为
     * {@code https://live.example.com/live/{roomId}/{streamKey}/index.m3u8?expire=&sign=}。
     *
     * <p>签名是按 URL 的 path 部分算的，必须与 Nginx 看到的 {@code $uri} 逐字一致；
     * 若播放口挂在 CDN 后面且 CDN 改写了路径，这里要填改写后的对外前缀。
     */
    private String playBaseUrl = "";

    /**
     * 推流基址：ffmpeg {@code -method PUT} 的目标前缀，例 {@code http://push.example.com/push/live}。
     * 推流口只在服务端与主播之间用，通常不对外发布，可以与播放口不同域名/端口。
     */
    private String pushBaseUrl = "";

    /**
     * 播放地址签名密钥，必须与 Nginx {@code secure_link_md5} 用的密钥一致。
     *
     * <p>算法固定为 {@code md5(uri + expire + secret)}（小写十六进制），
     * 与 Nginx 的 {@code secure_link_md5 "$uri$arg_expire$secret"} 逐字对应。
     * 生产环境务必改成随机长串并经环境变量注入，不要用仓库里的默认值。
     */
    private String signSecret = "im-live-dev-secret-change-me";

    /** 播放地址有效期（秒）。默认 6 小时：覆盖一场直播，且泄露的地址不会长期可用 */
    private long signTtlSeconds = 6 * 3600L;

    /**
     * 推流端心跳间隔建议值（秒），随开播响应下发；服务端按 2 倍判超时。
     * 心跳只写 Redis 的 TTL 键，不落库——15 秒一次 UPDATE 换不来任何信息。
     */
    private int heartbeatSeconds = 15;

    /** 心跳超时倍数：超过 {@code heartbeatSeconds * 该倍数} 没心跳即判定断播并自动关播 */
    private int heartbeatTimeoutFactor = 4;

    /** 单条弹幕最大字符数，超出直接拒绝（不是截断：截断会让用户以为自己发出去了完整内容） */
    private int danmakuMaxLength = 100;

    /** 单个连接每秒最多发几条弹幕。直播弹幕是高频轻量消息，不限流一个脚本就能刷爆房间 */
    private int danmakuPerSecond = 3;

    /** 房间标题最大长度 */
    private int titleMaxLength = 60;

    /** 单个用户同时能开几个直播中的房间。默认 1：多开只会分散带宽，且推流端也只有一台机器 */
    private int maxLivingRoomsPerUser = 1;

    /**
     * 在线人数计数键的 TTL（秒）。
     *
     * <p>正常路径下连接建立/断开会成对增减，TTL 只是兜底：进程被 kill、连接泄漏时
     * 计数不会永久停在错误值上，最多一个 TTL 后自动归零重来。
     */
    private long onlineKeyTtlSeconds = 2 * 3600L;
}
