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

    /**
     * 已结束房间在大厅列表的可见时长（分钟）。关播超过这个时长的房间不再出现在房间列表里，
     * 免得观众翻到一堆早就散场的历史场次。默认 60 分钟；直播中 / 已封禁的房间不受此限，
     * 配成 0 或负数则关闭过滤（历史场次全部保留）。
     *
     * <p>只影响列表展示：房间详情接口与「我的直播」面板仍可按 id 取到，不做隐藏。
     */
    private int endedRoomVisibleMinutes = 60;

    /**
     * 关播后房间保留「聊天模式」的时长（分钟）。关播时画面立即作废（streamKey 置空、
     * 观众端黑屏），但弹幕 WS 保留，观众可继续在房间里聊天；到时后巡检任务下发系统提示
     * 并断开全部连接、彻底关房。默认 60 分钟；配成 0 或负数则关闭聊天模式（关播立即断开，回到旧行为）。
     *
     * <p>已封禁的房间不进聊天模式：管理端强制断播应当立即生效。
     */
    private int endedChatMinutes = 60;

    /**
     * 弹幕是否套用全局敏感词遮罩（{@code SensitiveWordFilter.mask}）。
     * 默认开：弹幕原本不过滤，是内容合规的一个缺口；遮罩而非拒发，不把观众推到别的房间去骂。
     */
    private boolean danmakuSensitiveMask = true;

    /** 单个主播可自定的弹幕屏蔽词个数上限（词表只存 Redis，不设上限会让一个 Set 无限长） */
    private int danmakuWordsMaxCount = 50;

    /** 单个弹幕屏蔽词的最大长度（过长的句子当词用基本不会命中，白占内存） */
    private int danmakuWordMaxLength = 20;

    /**
     * 禁言名单与屏蔽词集合的滑动保留期（天）。
     *
     * <p>这两份数据只存 Redis、没有对应的表，所以保留期就是它们的实际寿命：
     * 每次读写都把整键 TTL 推后这么远（主播天天开播则名单不会老化）。
     * 一年后不再开播的主播，名单自然消失——这比在 Redis 里养一堆永久键好。
     */
    private int moderationRetentionDays = 365;

    /**
     * 主播屏蔽词在本机的缓存时长（毫秒）。弹幕是高频路径，每条都 SMEMBERS 一次太贵，
     * 而词表变更是低频事件。代价：多实例部署时其他实例最多滞后这么久（与本模块
     * 已有的「房间连接表只在本机」同一约束）；禁言判定<b>不</b>走缓存，必须实时生效。
     */
    private long danmakuWordsCacheMillis = 5000L;

    /**
     * 连麦 PK（两个直播中的房间比热度）。单独嵌一层是因为它有 7 个旋钮，
     * 平铺到 {@code im.live.*} 里会把房间生命周期配置隔成一堆。
     *
     * <p>热度只算「时间窗内的增量」：开场时采一次基线，之后每拍用当前累计值减基线。
     * 不减基线的话，主播先自己刷了十分钟热度再拉人 PK，对方一进来就输了。
     */
    @Data
    public static class Pk {

        /**
         * 总开关：关掉则 {@code /api/live/pk/**} 直接拒。前端不为这一个运维旋钮再加一个
         * 配置接口——按钮照旧显示，点下去拿到「PK 功能未开启」的提示即可。
         */
        private boolean enabled = true;

        /** 默认对局时长（秒），抖音式的一轮 PK 量级；前端不传时长就用它 */
        private int durationSeconds = 300;

        /** 时长上限（秒）：不设上限可以让主播开一场永久 PK，把两个房间的观众锁死 */
        private int maxDurationSeconds = 900;

        /**
         * 礼物权重（每 1 积分折多少热度）。礼物是真金白银，占主导；
         * 调成 0 就变成纯人气（点赞 + 投票）对局。
         */
        private int giftWeight = 10;

        /**
         * 点赞权重。免费行为所以给得小：百人房间每人每 2 秒一次能刷出 50/秒，
         * 而一个 100 积分的礼物是 1000 热度，刷赞不能直接赢下对局。
         */
        private int likeWeight = 1;

        /** 一票折多少热度（人头鼓励，不能盖过礼物） */
        private int voteWeight = 2;

        /** 已结束（或提前取消）的 PK 会话与票数的保留时长（分钟），供面板回看与防同一对人反复刷票 */
        private int sessionRetentionMinutes = 1440;
    }

    private Pk pk = new Pk();

    /**
     * 直播回放存档。<b>零 DDL</b>：分片本体一直存在媒体服务器上（推流时就已经落盘，不为本功能新增
     * 一次写入），Redis 里只有「这一场有回放」的展示元数据。
     *
     * <p>{@code retentionDays} 是回放的<b>统一保留期</b>：它既是 Redis 入口标记的 TTL，也是媒体侧
     * 删源文件的口径——真正的删除不在本进程做（控制面不碰媒体文件），而是由 {@code electron/live-retention.js}
     * 在媒体服务器那台机器上按同一保留期周期清理（自测的 live-media-server.js 已内置）。
     * 两边读同一个环境变量 {@code IM_LIVE_REPLAY_RETENTION_DAYS}：只改了本进程的 yml、没把同一个值交给
     * 媒体机，两边就会飘（要么入口先没、要么盘上多留几天，机理与命令见 md/直播.md §10.7）。
     */
    @Data
    public static class Replay {

        /**
         * 总开关：关掉后上报接口拒写、前端 stopPush 也不会去生成 VOD 清单
         * （开播响应里带 {@code replayEnabled}，推流端据此决定要不要做定版）。
         */
        private boolean enabled = true;

        /**
         * 回放保留期（天）：同一个值管两件事——Redis 入口键的 TTL，以及媒体侧源文件的清理线。
         * 默认 1 天：一天的旧直播基本没人看，而一场 2 小时四档直播在盘上是 8~10GB 量级。
         * 下限 1 天（{@code Math.max} 夹住），配成 0 也一样——那样入口和画面同一天消失，直播一关就没回放。
         */
        private int retentionDays = 1;

        /**
         * 上报时长的上限（小时）：分片数与时长都是推流端上报的，服务端无中立数据源可校对，
         * 只能夹一个量级。超出按时长上限取值（不拒写）——一个跳了边的统计不值得丢掉整场入口。
         */
        private int maxDurationHours = 12;
    }

    private Replay replay = new Replay();
}
