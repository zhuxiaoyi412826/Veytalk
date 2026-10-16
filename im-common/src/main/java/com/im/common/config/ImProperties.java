package com.im.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private Cache cache = new Cache();
    private Log log = new Log();
    private Moderation moderation = new Moderation();
    private Call call = new Call();
    private Intelligence intelligence = new Intelligence();
    private Wallet wallet = new Wallet();
    private LinkMic linkmic = new LinkMic();

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
        /**
         * SMTP 未配置（缺 {@code MAIL_USERNAME} / {@code MAIL_PASSWORD}）时，
         * 邮件验证码是否降级为「只写日志 + 按 expose-email-code 回显」而不报 MAIL_NOT_CONFIGURED。
         *
         * <p>存在的意义：邮箱登录与邮箱找回密码都依赖真实 SMTP，开发机上没有 QQ 授权码时
         * 这两条链路根本无法联调。打开后验证码仍然正常写入 Redis 并参与校验，只是不真发信。
         * 生产环境必须为 false：否则任何人都能靠回显拿到验证码，邮箱这道身份校验直接失效。
         */
        private boolean mockMailWhenUnconfigured = false;
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

    /**
     * 后端三级缓存（L1 Caffeine 本地 → L2 Redis 分布式 → L3 MySQL）。
     *
     * <p>读写约定见 {@code com.im.common.cache.ThreeLevelCache}：读路径逐层回源、
     * 命中后逐层回填；写路径由业务在更新后主动 evict（失效 L1+L2）。
     * CDN 层本期不实现：静态资源缓存由部署层 Nginx 的 Cache-Control 承担。
     *
     * <p>多节点部署时 evict 只失效本节点的 L1，其他节点靠 {@link #l1TtlSeconds}
     * 的短 TTL 兜底收敛，因此 L1 的 TTL 是「脏读窗口上限」，不要调得过大。
     */
    @Data
    public static class Cache {
        /** 总开关：关掉后所有 get 直接回源，排查缓存一致性问题时可临时使用 */
        private boolean enabled = true;
        /** L1 最多缓存多少个 key（按条目数淘汰，防堆内存膨胀） */
        private long l1MaxSize = 10_000;
        /** L1 写入后过期秒数，也是多节点间脏数据的最大收敛窗口 */
        private long l1TtlSeconds = 120;
        /** L2（Redis）默认过期秒数，单个调用点可用 get 的重载覆盖 */
        private long l2TtlSeconds = 1800;
        /** 空值哨兵在 L2 的过期秒数：防缓存穿透，又不至于让新建数据长期隐身 */
        private long nullTtlSeconds = 60;
    }

    /**
     * 图片内容审核（鉴黄）策略。
     *
     * <p>推理跑在发送端浏览器里，两个模型两套运行时：色情用 nsfwjs + TensorFlow.js
     * （模型权重内置在 npm 包里，随前端构建产物离线分发）；血腥 / 暴力用独立的一份 ONNX
     * 加 onnxruntime-web（见下）。服务端只做两件事：把阈值下发给客户端、接收命中上报
     * 并落审计通道。之所以不在服务端跑模型：内网部署无外网出口，模型文件与推理运行时
     * 都得随包落地，而同一份 MobileNetV2 权重在前端 WebGL 上单张只要几十毫秒，
     * 没有把它搬到服务端的收益。
     *
     * <p>代价必须知道：客户端的判定它可以绕过（改前端代码或直接调上传接口），
     * 这套闸门拦的是「正常使用路径下的违规内容」，不是对抗性防御。
     * 要升级成真防线，得在服务端补一条同样的推理链路（阈值与上报口径可以原样复用）。
     */
    @Data
    public static class Moderation {
        /** 总开关：关掉后策略接口回 enabled=false，前端整个识别环节直接跳过 */
        private boolean enabled = true;
        /** 命中后是否让客户端上报审计；关掉只拦不记，审计通道不再写条目 */
        private boolean auditEnabled = true;
        /**
         * 判违规的类别与阈值：类别名 → 概率（0~1），任一类别命中即拒发。
         *
         * <p>key 与前端模型的输出同名，本服务只原样下发、不校类别名：
         * nsfwjs 输出 Drawing / Hentai / Neutral / Porn / Sexy，
         * 血腥 / 暴力那个 ONNX 模型只输出 NonViolent / Violent。
         * 匹配在 im-ui/src/utils/nsfw.js 里做（忽略大小写），
         * 没写进这个 Map 的类别一律不参与判定——默认不放 Sexy 是因为它在泳装、健身照上
         * 误报严重，把它加进来会拦掉大量正常图片。
         *
         * <p>Drawing（二次元色情插画）与 Hentai 是模型独立输出的一类，
         * 只写实照片的人可以只保留 Porn。
         *
         * <p>加一行 {@code Violent: 0.75} 就会激活血腥 / 暴力检测（推理运行时随前端构建产物
         * 发出，不必单独配），但那个模型本体约 83MB 不入库，得运维先把它投放到
         * im-ui/public/models/violence/model.onnx；没投放时前端首次加载 404 会直接停用该检测器
         * （色情检测不受影响）。投放步骤与阈值起点理由见 md/图片内容审核接入指南.md。
         */
        private Map<String, Double> thresholds = new LinkedHashMap<>(Map.of("Porn", 0.80, "Hentai", 0.80));
        /** 上报里允许携带的评分条目数，防御性上限：超出的部分直接丢弃不入库 */
        private int maxScoreEntries = 16;
        /** 上报里文件名与场景等字符串字段的最大长度 */
        private int maxTextLength = 120;
    }

    /**
     * 音视频通话（一对一 WebRTC）。
     *
     * <p>媒体面走浏览器 / Electron 原生 WebRTC，服务端不参与媒体转发，只下发 ICE 服务器清单与
     * 转发信令（信令复用已认证的 WebSocket 长连接，见 im-websocket 的 CallSignalingService）。
     *
     * <p>内网部署下 {@link #stunUrls} 留空即可：两端同处一个局域网时，ICE 的 host 候选地址直接
     * 命中，媒体流点对点走局域网，既不经过服务器也不消耗服务器出口带宽。只有需要跨 NAT / 公网
     * 互通时才配自建 STUN，对称 NAT 还得配 {@link Turn}（coturn）。
     */
    @Data
    public static class Call {
        /** 总开关：关掉后 /api/call/config 回 enabled=false，前端通话入口整体禁用 */
        private boolean enabled = true;
        /**
         * STUN 服务器清单（形如 {@code stun:ip:3478}）；留空表示只做局域网直连，不探测公网映射地址。
         * 默认空列表是刻意的：本项目定位内网 / 离线，不能默认指向任何外部公网 STUN。
         */
        private List<String> stunUrls = List.of();
        /** TURN 中继：跨公网且对称 NAT 打不通时的兜底，默认关闭 */
        private Turn turn = new Turn();
        /** 振铃超时（秒）：被叫在此时长内未接听，服务端主动下发 timeout 并结束 */
        private int ringTimeoutSeconds = 45;
        /** 单通话最大时长（秒），防御性上限，超时由服务端下发 hangup 兜底 */
        private int maxDurationSeconds = 3600;
        /**
         * 通话录制产物上传的大小上限，默认 200MB。
         * 桌面端读回本地录像文件时按此裁剪，超限即拒传（{@code CALL_RECORD_TOO_LARGE}），
         * 避免一场超长通话的录像把渲染进程整块读进内存。
         */
        private DataSize recordMaxSize = DataSize.ofMegabytes(200);

        @Data
        public static class Turn {
            private boolean enabled = false;
            /** TURN 服务器清单（形如 {@code turn:host:3478?transport=udp}） */
            private List<String> urls = List.of();
            /**
             * coturn REST 长效凭据密钥（realm 需与此 secret 配套）。
             * 服务端按 {@code 过期时间戳:userId} 生成 username、用本密钥做 HMAC-SHA1 生成临时 credential，
             * 与 coturn 的 {@code use-auth-secret} 一致。为空则不下发 TURN。
             */
            private String secret = "";
            /** 临时凭据有效期（秒），默认 24 小时 */
            private long ttlSeconds = 86400;
        }
    }

    /**
     * 日志落地配置。
     *
     * <p>注意读取路径不经过这个类：logback 初始化早于 Spring 容器，它只能按属性名
     * 用 {@code <springProperty>} 从 Environment 里取（见 im-common 的 logback-spring.xml）。
     * 这里定义一份是把 key 和默认值写进类型系统，同时给 {@code SlowSqlInterceptor}
     * 提供阈值；两边默认值必须一致，改一处就得改两处。
     *
     * <p>体积类字段用 {@code String} 而不是像 {@link DataSize} 那样转成类型：这些字符串要直接交给
     * logback 解析（它认 {@code 50MB} / {@code 5GB} 这种写法），中间转一道单位对象只会多出一种表达形式。
     */
    @Data
    public static class Log {
        /**
         * 日志根目录。子目录结构（DEBUG.log / info-yyyy-MM-dd.N.log / SQL/）由 logback 定，
         * 这里只能改根。写相对路径也行，但生产机器上的工作目录不可控，绝对路径更保险。
         */
        private String home = "D:/rizi1/IM";
        /** 单条语句超过这个耗时才记为慢 SQL（毫秒）；<=0 表示关掉拦截器 */
        private long slowSqlMillis = 100;
        /** 单个日志文件的体积上限，到了就滚下一个序号，防单日日志过大占满磁盘 */
        private String maxFileSize = "50MB";
        /** 普通档保留天数 */
        private int maxHistory = 30;
        /** 单个 appender 的归档总体积上限，天数没到期但体积超了也会删最旧的 */
        private String totalSizeCap = "5GB";
        /** 错误档单独留更久：出事时最想翻的就是历史错误，而它的量级最小 */
        private int errorMaxHistory = 60;
    }

    /**
     * 外部智能能力（语音转文字 ASR / 消息翻译）。
     *
     * <p>这两项能力本质依赖外部服务（云厂商 ASR/翻译 API），本期内网/离线部署不接入任何真实厂商：
     * 接口与调用链路以 SPI 形式留好扩展点（{@code im-common} 的 {@code SpeechToTextSpi}/{@code TranslationSpi}），
     * 未接入时降级回 {@code MESSAGE_TO_TEXT_UNAVAILABLE}/{@code MESSAGE_TRANSLATE_UNAVAILABLE}。
     *
     * <p>{@code mockWhenUnconfigured} 对应 {@code captcha.mockMailWhenUnconfigured} 的同构开发开关：
     * 打开后内置桩实现返回一段标注清晰的占位文本，用于联调时跑通“UI→接口→SPI→结果展示”全链路；
     * 生产环境必须为 false（否则会把假转写结果当成真内容展示）。接真实厂商时提供一个 {@code @Primary}
     * 的 SPI 实现即可自动覆盖桩。
     */
    @Data
    public static class Intelligence {
        /** 能力总开关：关掉后前端菜单直接隐藏转文字/翻译入口 */
        private boolean enabled = true;
        /** 未接入真实厂商时是否用内置桩返回演示占位文本（仅开发联调，生产必须 false） */
        private boolean mockWhenUnconfigured = false;
        /** 翻译默认目标语言（Bilibili 风格 ISO 代码，如 zh-CN/en-US），前端未传时用它兑底 */
        private String defaultTargetLang = "en-US";
    }

    /**
     * 虚拟积分/币钱包与礼物体系。
     *
     * <p>不涉真实货币：充值依赖外部支付厂商，本期内网/离线部署不接入，用
     * {@link #mockWhenUnconfigured} 对应的模拟桩（{@code MockPaymentSpi}）直接按请求额到账跑通链路，
     * 取向与 {@code im.intelligence.mock-when-unconfigured}、{@code captcha.mockMailWhenUnconfigured} 完全一致；
     * 接真实支付时提供一个 {@code @Primary} 的 {@code PaymentSpi} 实现即覆盖桩，业务代码不变，届时本开关必须为 false。
     */
    @Data
    public static class Wallet {
        /** 钱包总开关：关掉后送礼/充值/签到入口整体拒绝（{@code WALLET_DISABLED}），前端礼簿隐藏 */
        private boolean enabled = true;
        /** 未接入真实支付时是否注册模拟支付桩（仅开发联调，生产必须 false，否则等于无限白送积分） */
        private boolean mockWhenUnconfigured = false;
        /** 每日签到赠送的积分 */
        private long checkinBonus = 20;
        /** 单次送礼数量上限，夹住前端传入的 count 防刷 */
        private int maxGiftCount = 99;
        /** 单笔充值积分下界 */
        private long rechargeMin = 1;
        /** 单笔充值积分上界 */
        private long rechargeMax = 100_000;
    }

    /**
     * 直播连麦（观众 ↔ 主播 1:1 WebRTC）。
     *
     * <p>媒体面与音视频通话同构：走浏览器 / Electron 原生 WebRTC，服务端不转发媒体，只搬运信令并下发 ICE
     * （信令复用已认证的 {@code /ws} 长连接，见 im-websocket 的 {@code LiveLinkSignalingService}；ICE 直接复用
     * {@code CallSignalingService.buildIceServers}，连麦与通话共用同一套 STUN/TURN 配置）。
     *
     * <p>配置刻意落在 {@code im.*}（im-common）而非 {@code im.live.*}（im-live）：连麦信令服务位于 im-websocket，
     * 而 im-websocket 只依赖 im-common，读不到 im-live 的 {@code LiveProperties}。
     */
    @Data
    public static class LinkMic {
        /** 总开关：关掉后连麦入口整体禁用，REST 回 enabled=false，WS 信令静默丢弃 */
        private boolean enabled = true;
        /** 单房间同时在麦嘉宾上限（主播之外的连麦路数），防御性上限，超限回 LIVE_LINK_FULL */
        private int maxSlots = 4;
        /** 上麦申请超时（秒）：主播在此时长内未应答，服务端主动下发 timeout 并作废该次申请 */
        private int requestTimeoutSeconds = 60;
        /** 单次连麦最长时长（秒），超时由服务端下发 leave 兜底 */
        private int maxDurationSeconds = 3600;
    }
}
