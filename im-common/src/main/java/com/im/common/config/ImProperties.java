package com.im.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * 项目自定义配置，统一挂载在 {@code im.*} 前缀下。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im")
public class ImProperties {

    private Jwt jwt = new Jwt();
    private Security security = new Security();
    private File file = new File();
    private Message message = new Message();
    private Websocket websocket = new Websocket();
    private Cors cors = new Cors();
    private Captcha captcha = new Captcha();
    private RateLimiting rateLimit = new RateLimiting();

    @Data
    public static class Jwt {
        /** 短时票据签名密钥，与 sa-token.jwt-secret-key 独立 */
        private String secret = "im-default-jwt-secret-please-change-in-production-0123456789";
        /** WebSocket 连接票据有效期（秒） */
        private long ticketTtlSeconds = 60;
        /**
         * 文件临时访问票据有效期（秒）。
         *
         * <p>不能太短：浏览器渲染 {@code <img src>} 时无法携带登录头，只能靠 URL 上的票据，
         * 票据一过期图片就全碎；也不能太长，因为 URL 一旦转手就等于把文件交了出去。
         */
        private long fileTicketTtlSeconds = 1800;
    }

    @Data
    public static class Security {
        /** 密码加密算法：argon2 | bcrypt */
        private String passwordEncoder = "argon2";
    }

    @Data
    public static class File {
        /** 存储实现：local | minio */
        private String storage = "local";
        /** 受控下载地址前缀 */
        private String accessUrlPrefix = "/api/file/download";
        /** 是否对图片返回预签名直链 */
        private boolean usePresignedUrl = false;
        /**
         * 聊天附件大小上限，默认 20MB。
         *
         * <p>用 {@link DataSize} 而不是 {@code long}：配置文件里就能写 {@code 20MB}，
         * 与 {@code spring.servlet.multipart.max-file-size} 的写法一致。
         * 两个限制必须一起调——只改这一个的话，请求会先被 multipart 的上限拦下来，
         * 抛的是 {@code MaxUploadSizeExceededException}，而不是本项对应的 {@code UPLOAD_TOO_LARGE}，
         * 报错信息与真正起作用的那道限制对不上，排查时很容易被带偏。
         */
        private DataSize maxSize = DataSize.ofMegabytes(20);
        /**
         * 头像大小上限，默认 2MB。
         *
         * <p>头像会被会话列表、好友列表、群成员列表反复拉取，
         * 没必要允许传一张原图；而普通聊天图片仍然适用 {@link #maxSize}。
         */
        private DataSize maxAvatarSize = DataSize.ofMegabytes(2);
        private Local local = new Local();
        private Minio minio = new Minio();
        private Upload upload = new Upload();

        @Data
        public static class Local {
            /** 本地存储根目录，默认用户主目录下的 im-files */
            private String baseDir = System.getProperty("user.home") + "/im-files";
        }

        @Data
        public static class Minio {
            private String endpoint = "http://127.0.0.1:9000";
            private String accessKey = "minioadmin";
            private String secretKey = "minioadmin";
            /**
             * 桶名，必须遵循 Amazon S3 命名规范：至少 3 个字符，只能含小写字母/数字/点/连字符。
             *
             * <p>不能图短用 {@code "im"}：MinIO SDK 的 {@code validateBucketName} 对少于 3 字符的桶名
             * 直接抛 {@code IllegalArgumentException}，会让 {@code MinioFileStorage} 构造失败、应用启动中断。
             */
            private String bucket = "im-files";
        }

        /**
         * 分片上传（断点续传）相关配置。
         *
         * <p>分片先落本地临时目录，合并后再写入真正的存储实现（local 或 minio），
         * 因此无论最终存哪里，临时目录都必须可读写。分片上传刻意不复用
         * {@link File#getMaxSize()}：单个分片只有几 MB，远低于 multipart 的硬上限，
         * 而整体文件可以很大（大视频），两道限制的职责本就不同。
         */
        @Data
        public static class Upload {
            /** 分片临时目录，合并成功后即清理；过期会话由定时任务回收 */
            private String tmpDir = System.getProperty("user.home") + "/im-upload-tmp";
            /** 服务端权威分片大小，默认 5MB；init 时下发给前端，前端必须按它切片 */
            private DataSize chunkSize = DataSize.ofMegabytes(5);
            /** 单个分片请求体的上限，默认 6MB，给 chunkSize 留出表单字段等开销余量 */
            private DataSize maxChunkSize = DataSize.ofMegabytes(6);
            /** 走分片通道的整体文件大小上限，默认 2GB */
            private DataSize maxSize = DataSize.ofGigabytes(2);
            /** 上传会话（临时分片）保留时长（秒），默认 24 小时，超时由清理任务回收 */
            private long sessionTtlSeconds = 24 * 60 * 60;
            /** 单文件最多分片数，防御性上限，避免 totalChunks 被算成天文数字撑爆磁盘 */
            private int maxChunks = 100_000;
        }
    }

    @Data
    public static class Message {
        /** 撤回时限（秒），默认 2 小时 */
        private int recallLimitSeconds = 7200;
        /** 历史消息默认每页条数 */
        private int historyPageSize = 20;
        /** 文本消息最大长度 */
        private int maxTextLength = 5000;
        /**
         * 敏感词过滤开关：命中后遮成 * 再落库投递（非拒发）。
         * 只在词库装载成功时生效，关掉可减少一条纯文本消息的扫描开销。
         */
        private boolean sensitiveFilterEnabled = true;
        /** 消息保留天数，超期由定时任务物理清理；0 表示永不清理（默认） */
        private int retentionDays = 0;
    }

    @Data
    public static class Websocket {
        /**
         * 建议客户端心跳间隔（秒），握手成功后随欢迎帧下发给前端。
         *
         * <p>这个值不驱动服务端任何行为，只是告诉客户端「你多久该发一次 ping」，
         * 前端不必自己硬编码，改这里就能统一调整全网的心跳频率。
         */
        private int heartbeatIntervalSeconds = 30;
        /** 心跳超时（秒），超过则服务端主动断开。必须是心跳间隔的 2~3 倍，留足网络抖动余量 */
        private int heartbeatTimeoutSeconds = 90;
        /** 空闲检查任务执行间隔（毫秒） */
        private long idleCheckIntervalMs = 30000;
        /**
         * 单条文本帧的字节上限，默认 64KB。
         *
         * <p>Tomcat 的默认值是 8KB，而 {@code im.message.max-text-length} 允许 5000 个字符，
         * 中文在 UTF-8 下每字 3 字节，一条长消息就有 15KB——不抬这个限制，
         * 长文本会直接被容器以 {@code TOO_BIG} 关闭连接，且服务端日志里看不到任何业务异常。
         */
        private int maxTextMessageBytes = 64 * 1024;
        /**
         * 单条报文的最长发送耗时（毫秒），交给 {@code ConcurrentWebSocketSessionDecorator}。
         *
         * <p>慢客户端（弱网、页面被挂起）会让 {@code sendMessage} 长时间阻塞，
         * 而推送发生在业务线程上，一个卡住的连接足以拖慢整个群发的循环。
         * 超过这个时间装饰器会直接判定该会话不可靠并关闭它，而不是让整个线程陪着等。
         */
        private int sendTimeLimitMs = 10_000;
        /**
         * 单个会话允许积压的发送缓冲区字节数，超过即关闭会话。
         *
         * <p>与 {@link #sendTimeLimitMs} 是一对互补的护栏：前者管「单次发送太慢」，
         * 这个管「一直发不出去越积越多」。不设上限的话，一个僵尸连接能吃掉等量堆内存。
         */
        private int sendBufferSizeBytes = 512 * 1024;
    }

    @Data
    public static class Cors {
        /**
         * 允许的前端来源，按 origin pattern 匹配（见 {@code CorsConfig}）。
         *
         * <p>后两条是局域网真机联调用的：手机用电脑的局域网 IP 打开页面时，Origin 会变成
         * {@code http://192.168.x.x:5173} 这类地址。vite 代理的 {@code changeOrigin} 只改
         * Host 头不改 Origin 头，而后端判定 CORS 请求只看有没有 Origin 头，所以照样会走到
         * 这里的白名单校验。
         *
         * <p>写成 {@code *.*.*.*} 而不是 {@code *}：四段通配只匹配 IPv4 形式的 origin，
         * 域名一律拒绝，端口也锁死；单写 {@code *} 会把任意域名的 5173 端口一并放行。
         * 部署到公网前必须换成真实域名。
         */
        private List<String> allowedOrigins = List.of(
                "http://localhost:5173", "http://127.0.0.1:5173",
                "http://localhost:4173", "http://127.0.0.1:4173",
                "http://*.*.*.*:5173", "http://*.*.*.*:4173");
    }

    @Data
    public static class Captcha {
        /** 图形验证码有效期（秒） */
        private long imageTtlSeconds = 300;
        /** 短信验证码有效期（秒） */
        private long smsTtlSeconds = 300;
        /** 同一手机号短信发送最小间隔（秒） */
        private long smsIntervalSeconds = 60;
        /** 邮箱验证码有效期（秒） */
        private long emailTtlSeconds = 300;
        /** 同一邮箱验证码发送最小间隔（秒） */
        private long emailIntervalSeconds = 60;
        /** 是否在响应中回显短信验证码，仅开发环境使用 */
        private boolean exposeSmsCode = true;
        /** 是否在响应中回显邮箱验证码，仅开发环境使用 */
        private boolean exposeEmailCode = false;
        /** 发送短信验证码前是否强制先校验图形验证码（防短信轰炸的闸门） */
        private boolean imageRequired = true;
        /** 是否在响应中回显图形验证码答案，仅开发环境联调使用 */
        private boolean exposeImageCode = false;
    }

    /**
     * 接口限流。端点级配额由 {@code @RateLimit} 注解声明，
     * 这里只配总开关、全局按 IP 兜底与 WebSocket 发消息频率。
     */
    @Data
    public static class RateLimiting {
        /** 总开关：关掉后注解配额与全局兜底全部失效，仅供排障时临时使用 */
        private boolean enabled = true;
        /**
         * 全局兜底：单 IP 在一个窗口内允许的最大请求数，覆盖所有未标注解的接口。
         *
         * <p>默认 300 次/10 秒（约 30 QPS/IP）：正常前端会话列表+历史分页+媒体拉取
         * 的峰值远低于这个值，而脚本刷接口会在几秒内撞上它。
         * 内网多人共用出口 IP 部署时需按人数上调。
         */
        private int globalCount = 300;
        private int globalSeconds = 10;
        /** WebSocket 通道单用户发消息频率：超限回 error 帧（code=1010），不断连接 */
        private int wsChatCount = 60;
        private int wsChatSeconds = 60;
    }
}
