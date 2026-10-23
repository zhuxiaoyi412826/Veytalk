package com.im.common.constant;

/**
 * Redis 键名规范，统一以 {@code im:} 前缀开头。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 全局键前缀 */
    public static final String PREFIX = "im:";

    /** 图形验证码：im:captcha:image:{captchaKey} -> 验证码答案 */
    public static final String CAPTCHA_IMAGE = PREFIX + "captcha:image:";

    /** 短信验证码：im:captcha:sms:{scene}:{phone} -> 6 位验证码 */
    public static final String CAPTCHA_SMS = PREFIX + "captcha:sms:";

    /**
     * 短信发送频率限制：im:captcha:sms:limit:{phone}
     *
     * <p>刻意不按场景分键：否则攻击者轮流用 login / reset 两个场景发码，
     * 就等于把「同一手机号 60 秒一次」的限制翻了一倍，短信费用与骚扰风险同步放大。
     */
    public static final String CAPTCHA_SMS_LIMIT = PREFIX + "captcha:sms:limit:";

    /** 邮箱验证码：im:captcha:email:{scene}:{email} -> 6 位验证码 */
    public static final String CAPTCHA_EMAIL = PREFIX + "captcha:email:";

    /** 邮箱验证码发送频率限制：im:captcha:email:limit:{email}，同样不按场景分键 */
    public static final String CAPTCHA_EMAIL_LIMIT = PREFIX + "captcha:email:limit:";

    /** 在线状态：im:online:{userId} -> Hash(deviceId -> 最近心跳时间戳) */
    public static final String ONLINE = PREFIX + "online:";

    /** 会话消息序列：im:conv:seq:{conversationId} -> 自增序号 */
    public static final String CONV_SEQ = PREFIX + "conv:seq:";

    /** 用户未读总数缓存：im:unread:{userId} */
    public static final String UNREAD_TOTAL = PREFIX + "unread:";

    /** 消息幂等标记：im:msg:idem:{userId}:{clientMsgId} -> MessageDTO JSON */
    public static final String MSG_IDEMPOTENT = PREFIX + "msg:idem:";

    /** 用户角色权限缓存：im:auth:perm:{userId} / im:auth:role:{userId} */
    public static final String AUTH_PERMISSION = PREFIX + "auth:perm:";
    public static final String AUTH_ROLE = PREFIX + "auth:role:";

    /** 被 @ 提醒标记：im:at:{userId} -> Set(conversationId) */
    public static final String AT_ME = PREFIX + "at:";

    /** 文件 MD5 秒传索引：im:file:md5:{md5} -> fileId */
    public static final String FILE_MD5 = PREFIX + "file:md5:";

    /**
     * 全网检索结果缓存：im:web:search:{keywordMd5} -> WebSearchVO JSON。
     *
     * <p>键用关键字的 MD5 而不是原文：关键字是用户任意输入，可能含中文、空格、
     * Redis 键分隔符 {@code :}，也可能是几百字的长句。
     */
    public static final String WEB_SEARCH = PREFIX + "web:search:";

    public static String captchaImage(String captchaKey) {
        return CAPTCHA_IMAGE + captchaKey;
    }

    /**
     * 短信验证码键，按场景隔离。
     *
     * <p>场景进键名是安全边界而不是整洁度问题：登录、绑定、找回密码三个场景
     * 的信任级别不同，找回密码能直接改写密码。若共用一个键，用户为登录申请的
     * 验证码就能被拿去重置密码（反之亦然），等于把改密码这道门的强度降到了登录码的水平。
     */
    public static String captchaSms(String scene, String phone) {
        return CAPTCHA_SMS + scene + ":" + phone;
    }

    public static String captchaSmsLimit(String phone) {
        return CAPTCHA_SMS_LIMIT + phone;
    }

    /** 邮箱验证码键，按场景隔离，理由同 {@link #captchaSms(String, String)} */
    public static String captchaEmail(String scene, String email) {
        return CAPTCHA_EMAIL + scene + ":" + email;
    }

    public static String captchaEmailLimit(String email) {
        return CAPTCHA_EMAIL_LIMIT + email;
    }

    public static String online(Long userId) {
        return ONLINE + userId;
    }

    public static String convSeq(Long conversationId) {
        return CONV_SEQ + conversationId;
    }

    public static String unreadTotal(Long userId) {
        return UNREAD_TOTAL + userId;
    }

    public static String msgIdempotent(Long userId, String clientMsgId) {
        return MSG_IDEMPOTENT + userId + ":" + clientMsgId;
    }

    public static String authPermission(Long userId) {
        return AUTH_PERMISSION + userId;
    }

    public static String authRole(Long userId) {
        return AUTH_ROLE + userId;
    }

    public static String atMe(Long userId) {
        return AT_ME + userId;
    }

    public static String fileMd5(String md5) {
        return FILE_MD5 + md5;
    }

    public static String webSearch(String keywordMd5) {
        return WEB_SEARCH + keywordMd5;
    }

    /**
     * 直播间在线人数：im:live:online:{roomId} -> 计数。
     *
     * <p>不进库是因为它会每秒变好几次（百人房间进出频繁），而库值只在关播时定格一次。
     */
    public static final String LIVE_ONLINE = PREFIX + "live:online:";

    /**
     * 直播推流心跳：im:live:hb:{roomId} -> 时间戳，TTL 即超时判定。
     *
     * <p>用 TTL 而不是存时间戳再比差值：巡检任务只需 {@code EXISTS}，
     * 不必把全部直播中的房间扫一遍再逐个算超时。
     */
    public static final String LIVE_HEARTBEAT = PREFIX + "live:hb:";

    /**
     * 直播「推流端已跑起来」标记：im:live:pushed:{roomId} -> 首次心跳时间戳。
     *
     * <p>心跳键在开播建房时就播种了，区分不了「推流端真跑过」与「建完房 ffmpeg 就没起来」；
     * 这个键只在推流端首次调心跳接口时种，供 abort（撤销未推流房间）做安全护栏。
     */
    public static final String LIVE_PUSHED = PREFIX + "live:pushed:";

    /**
     * 本场直播的累计礼物热度：im:live:gift:heat:{roomId} -> 累计礼物价值（积分）。
     *
     * <p>用 Redis 计数不落库：热度只在直播过程中实时变，且是后续连麦 PK/热度榜的数据源；
     * 送礼流水本身已落 {@code im_gift_record}，热度只是它的一份易失聚合，房间结束后靠 TTL 自然回收。
     */
    public static final String LIVE_GIFT_HEAT = PREFIX + "live:gift:heat:";

    public static String liveOnline(Long roomId) {
        return LIVE_ONLINE + roomId;
    }

    public static String liveHeartbeat(Long roomId) {
        return LIVE_HEARTBEAT + roomId;
    }

    public static String livePushed(Long roomId) {
        return LIVE_PUSHED + roomId;
    }

    public static String liveGiftHeat(Long roomId) {
        return LIVE_GIFT_HEAT + roomId;
    }

    /**
     * 主播的弹幕禁言名单：im:live:mute:{anchorId} -> Hash（field=userId，value=禁言详情 JSON）。
     *
     * <p><b>按 anchorId 而不是 roomId 组织</b>：房间行是「一场直播」（每次开播新建一行），
     * 按房间存等于下一场那人又能进来捣乱，而主播心里想的「这个人我不想再看到」是跨场次的。
     *
     * <p>用 Hash + 值里带到期时间戳，而不是每人一个带 TTL 的键：① 面板要列全部被禁用户，
     * Hash 是一次 HGETALL，分散键得 SCAN（KEYS 会阻塞）；② Hash 的 TTL 只能整键生效，
     * 单人的「5 分钟 / 永久」只能靠读时比较 {@code expireAt} 判定 + 懒删，语义反而更准。
     *
     * <p>Redis 是这份名单<b>唯一的存储</b>（无对应表），整键 TTL 为滑动保留期，
     * 超期未访问的条目会随键一起消失——临时禁言本就无所谓，永久禁言若要真正永久需另建表。
     */
    public static final String LIVE_MUTE = PREFIX + "live:mute:";

    /**
     * 主播自定义的弹幕屏蔽词：im:live:words:{anchorId} -> Set（成员为折叠后的词）。
     *
     * <p>同样按 anchorId（跨场次生效）且不落表：词表是主播随手维护的小数据，
     * 全局敏感词走 {@code SensitiveWordFilter} 的 classpath 词库，这里只补「这个主播不想听到的词」。
     */
    public static final String LIVE_WORDS = PREFIX + "live:words:";

    public static String liveMute(Long anchorId) {
        return LIVE_MUTE + anchorId;
    }

    public static String liveWords(Long anchorId) {
        return LIVE_WORDS + anchorId;
    }

    /**
     * 直播间门禁（付费 / 密码）配置：im:live:gate:{roomId} -> JSON {mode, pwdHash, price}。
     *
     * <p><b>零 DDL</b>：门禁是「这一场」的瞬时属性（下一场可以换密码、换价、干脆不设），
     * 与 PK / 房管 / 回放同构——只存 Redis，不给 {@code im_live_room} 加列。键 TTL 随直播心跳
     * 续期，与直播同生命周期：直播一停、心跳不再续，门禁配置自然消失。
     *
     * <p>不存在该键 = 房间无门禁（免费公开），这是绝大多数房间的常态，所以列表接口逐行
     * 只会做一次 EXISTS 命中极少，与回放 {@code has()} 的取舍一致。
     */
    public static final String LIVE_GATE = PREFIX + "live:gate:";

    /**
     * 某用户对本房间的解锁态：im:live:unlock:{roomId}:{userId} -> "1"。
     *
     * <p>密码房：密码校验通过后种此键；付费房：扣款成功后种此键。有键即放行播放地址与弹幕端点。
     * TTL 与门禁配置同量级——主播下一场要重新解锁；观众中途重进本场则凭键免解锁。
     */
    public static final String LIVE_UNLOCK = PREFIX + "live:unlock:";

    public static String liveGate(Long roomId) {
        return LIVE_GATE + roomId;
    }

    public static String liveUnlock(Long roomId, Long userId) {
        return LIVE_UNLOCK + roomId + ":" + userId;
    }

    /**
     * 本场直播的累计点赞数：im:live:like:heat:{roomId} -> 计数。
     *
     * <p>原先点赞只在内存里合并（pendingLikes）+ 前端自己累加，服务端从来不知道总数，
     * 而 PK 热度需要一个能在任意实例上读到的房间维度量——所以下沉到 Redis。
     * 累加发生在 flush 节拍（每房间每 2 秒一次 INCRBY），不是每人每次点赞一次。
     */
    public static final String LIVE_LIKE_HEAT = PREFIX + "live:like:heat:";

    /**
     * PK 会话态：im:pk:session:{pkId} -> LivePkPO JSON（双方房间/主播/时间窗/基线热度/结果）。
     *
     * <p>与连麦态同构（都是带 TTL 的易失会话，不建表）：PK 只在两个房间同时直播时有意义，
     * 一方关播即作废，落库只会留下一堆没人看的比赛记录。结果也留在这里，
     * 供「主播数据面板」之类的事后查看按保留期读取。
     */
    public static final String LIVE_PK_SESSION = PREFIX + "pk:session:";

    /**
     * 房间维度的 PK 指针：im:pk:room:{roomId} -> pkId。
     *
     * <p>弹幕通道与前端都只知道 roomId，而会话键按 pkId 寻址；没有这个指针就只能扫键。
     * 一个房间同时只能有一场 PK（新开场覆盖旧值），因此它兼作「本房间 PK 进行中」的判定。
     */
    public static final String LIVE_PK_ROOM = PREFIX + "pk:room:";

    /**
     * PK 观众投票：im:pk:vote:{pkId} -> Hash（field=userId，value=站的那一边 A/B）。
     *
     * <p>Hash 天然去重（同 field 后写覆盖），所以「一人一票」不需要额外集合；
     * 代价是改票也会覆盖——热度只按 HLEN 算人头，改票等于换边，可接受。
     */
    public static final String LIVE_PK_VOTE = PREFIX + "pk:vote:";

    /**
     * 进行中的 PK 索引：im:pk:running -> Set(pkId)。
     *
     * <p>结算节拍需要一个能遍历的集合，而 {@code KEYS im:pk:session:*} 会阻塞 Redis；
     * 这个集合同时兼任「全局是否有人在 PK」的快速判定（绝大多数节拍上是空集，一次 SCARD 就返回）。
     * 条目由结算/过期路径负责移除，失败时靠会话键自身 TTL 兼做兜底。
     */
    public static final String LIVE_PK_RUNNING = PREFIX + "pk:running";

    public static String liveLikeHeat(Long roomId) {
        return LIVE_LIKE_HEAT + roomId;
    }

    public static String pkSession(String pkId) {
        return LIVE_PK_SESSION + pkId;
    }

    public static String pkRoom(Long roomId) {
        return LIVE_PK_ROOM + roomId;
    }

    public static String pkVote(String pkId) {
        return LIVE_PK_VOTE + pkId;
    }

    public static String pkRunning() {
        return LIVE_PK_RUNNING;
    }

    /**
     * 直播回放入口：im:live:replay:{roomId} -> LiveReplayPO JSON（分片数/时长/字节数/上报时刻）。
     *
     * <p><b>它不是回放的存储</b>：画面字节从推流那一刻起就在媒体服务器上（分片只增不删），
     * 清单地址又可由 roomId 确定性推导（{@code {roomId}/replay/index.m3u8}），所以这个键丢了
     * 回放仍然可播——只是前端拿不到入口与展示数据。因此它零 DDL、也不需要备份。
     *
     * <p>不建表的原因与 {@code im_file} 复用不通：{@code im_file} 没有 bizId 列，
     * 存不下「这份文件属于哪一场直播」，而为一行元数据新开一张表不值。
     */
    public static final String LIVE_REPLAY = PREFIX + "live:replay:";

    public static String liveReplay(Long roomId) {
        return LIVE_REPLAY + roomId;
    }

    /**
     * 个人常用语（快捷回复）：im:quick:reply:{userId} -> JSON 字符串数组（保序）。
     *
     * <p>用 String 而不是 List/Hash：它是「整个一份由本人维护的清单」，读全部、写全部，
     * 而顺序就是用户自己排的显示顺序（Set 会打乱，Hash 的 field 顺序不可控）。
     *
     * <p>不建表：与弹幕屏蔽词同属「低频写、小体量、只本人维护」的用户自定义数据。
     * 代价也一样说清——<b>Redis 是唯一存储</b>，靠滑动保留期续期；要跨设备长期可靠必须落表。
     * 之所以放服务端而不是像界面偏好那样进 localStorage：常用语是拿来发的，
     * 手机上没有桌面端那套话术会让「回复」这个动作直接消失。
     */
    public static final String QUICK_REPLY = PREFIX + "quick:reply:";

    public static String quickReply(Long userId) {
        return QUICK_REPLY + userId;
    }

    /**
     * 红包领取态：im:wallet:packet:{packetId} -> Hash（meta / splits / count / sum / claim:{userId}）。
     *
     * <p>为什么不建表：红包是「24 小时内要么被领完、要么过期退回」的短命状态，落表就是
     * {@code im_red_packet} + {@code im_red_packet_claim} 两张注定堆满死数据的表。
     *
     * <p><b>但钱不在这里</b>：发送时已经从余额真扣（一条 {@code packet} 负额流水），领取时才入账，
     * 所以这段差额只是「挂在红包池里」。即使这份 Redis 态整个丢掉也不会凭空少钱：
     * 过期退款任务不读这里，而是按 {@code ref_id = packetId} 对账本求和得出未退余额
     * （见 {@code WalletTransactionMapper.selectExpirablePackets}），丢了 Redis 只会让红包领不了，
     * 不会让钱回不来。这一点是它和「常用语」那种纯 Redis 存储最大的区别。
     */
    public static final String WALLET_PACKET = PREFIX + "wallet:packet:";

    /**
     * 过期退款互斥锁：im:wallet:packet:refund:{packetId}。
     *
     * <p>退款本身有账本求和兼作幂等（退过就不会再被扫出来），这把锁只防两个调度实例
     * 同时算出同一笔余额、双双入账的窗口。
     */
    public static final String WALLET_PACKET_REFUND = PREFIX + "wallet:packet:refund:";

    public static String walletPacket(Long packetId) {
        return WALLET_PACKET + packetId;
    }

    public static String walletPacketRefund(Long packetId) {
        return WALLET_PACKET_REFUND + packetId;
    }

    /**
     * 举报去重闸门：im:report:dedup:{reporterId}:{targetType}:{targetId} -> {@code "1"}（带 TTL）。
     *
     * <p>刻意<b>不在</b> {@code im_report} 上建唯一键：唯一键会把「同一对象被同一人隔几天再报一次」
     * 永久挡死，而「上次报了没处理，我再报一次」在举报场景里是合理诉求。
     * 所以这里只挡一段时间（默认 24 小时），过了就允许再提，工单表里留下多行也是真实的求助记录。
     *
     * <p>代价写在明处：Redis 丢了去重就失效，同一人可以连提多条，
     * 后果只是后台多几张重复工单，不动钱也不动任何业务数据。
     *
     * <p>类型串直接拼进键里，不做数字编码：这张表的键本来就要靠人肉 {@code redis-cli} 排查，
     * {@code im:report:dedup:12:message:345} 比 {@code im:report:dedup:12:3:345} 好认。
     */
    public static final String REPORT_DEDUP = PREFIX + "report:dedup:";

    /**
     * 举报日配额：im:report:quota:{reporterId}:{yyyyMMdd} -> 当日已提交条数。
     *
     * <p>键里带日期而不是靠 TTL「一天后过期」：后者要算「到明天零点的秒数」，
     * 一旦写成固定 24 小时就会滑到半夜才重置，用户体感是「昨天报满了今天还不能报」；
     * 带日期的键天然按自然日翻页，不需要任何组件去清。
     *
     * <p>日期取服务器本地时区（{@code LocalDate.now()}）：多实例跨时区部署时以机器时区为准，
     * 这个差异只会让配额重置时刻差几小时，不影响判定本身。
     */
    public static final String REPORT_QUOTA = PREFIX + "report:quota:";

    /**
     * 后台看板聚合缓存：im:admin:dashboard -> 概览 JSON。
     *
     * <p>看板是几条 {@code COUNT} 拼出来的，其中「今日消息数」在千万级表上不算便宜
     * （走 {@code im_message.idx_send_time} 的范围扫，仍然随当天量线性增长），
     * 而管理员刷新页面的频率远高于这些数字真正变化的意义。
     *
     * <p>只有一个键、全体管理员共享：看板数字与查看者无关，按人分键只会让缓存命中率更低。
     * 代价必须在页面上写清——<b>看板数字最多滞后一个缓存周期</b>，不是实时数。
     */
    public static final String ADMIN_DASHBOARD = PREFIX + "admin:dashboard";

    public static String reportDedup(Long reporterId, String targetType, Long targetId) {
        return REPORT_DEDUP + reporterId + ":" + targetType + ":" + targetId;
    }

    public static String reportQuota(Long reporterId, String day) {
        return REPORT_QUOTA + reporterId + ":" + day;
    }

    public static String adminDashboard() {
        return ADMIN_DASHBOARD;
    }
}
