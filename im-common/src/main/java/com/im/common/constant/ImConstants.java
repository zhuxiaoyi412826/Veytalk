package com.im.common.constant;

import java.util.List;

/**
 * 全局通用常量。
 */
public final class ImConstants {

    private ImConstants() {
    }

    /** 接口统一前缀 */
    public static final String API_PREFIX = "/api";

    /** WebSocket 端点 */
    public static final String WS_ENDPOINT = "/ws";

    /** 远程控制端点：被控端 Agent（sa-token 登录 token 握手）与控制端（一次性 ticket 握手） */
    public static final String WS_REMOTE_AGENT_ENDPOINT = "/ws/remote/agent";
    public static final String WS_REMOTE_CONTROL_ENDPOINT = "/ws/remote/control";

    /**
     * 直播弹幕端点：观众与主播都连这一条，握手参数为 roomId + satoken。
     *
     * <p>刻意不复用聊天通道 {@link #WS_ENDPOINT}：弹幕是高频、可丢弃、不落库的
     * 临时消息，而聊天通道每条消息都要落库、发号、推离线；两者混在一个端点上，
     * 百人房间的弹幕会把聊天推送的处理线程占满。同理也不走群消息：
     * 走群消息就意味着每个观众都要成为群成员、直播间会出现在所有人的会话列表里。
     */
    public static final String WS_LIVE_ENDPOINT = "/ws/live";

    /** Sa-Token 登录体系标识 */
    public static final String LOGIN_TYPE = "login";

    /** 请求头中的链路追踪 ID */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** MDC 中当前登录用户的键名 */
    public static final String MDC_USER_ID = "userId";

    /** 默认页码 */
    public static final long DEFAULT_PAGE_CURRENT = 1L;

    /** 默认每页条数 */
    public static final long DEFAULT_PAGE_SIZE = 20L;

    /** 每页最大条数，防止恶意大分页 */
    public static final long MAX_PAGE_SIZE = 100L;

    /** 文本消息最大长度 */
    public static final int MAX_TEXT_LENGTH = 5000;

    /** 消息撤回时限（秒） */
    public static final int RECALL_LIMIT_SECONDS = 120;

    /** 导出聊天记录：单会话一次导出的累积上限，防止超大会话把内存打爆（超出部分需分批/按时间范围导出） */
    public static final int MAX_EXPORT_MESSAGES = 20000;

    /** 导出聊天记录：游标翻页每批条数（服务端内部批量取数，不受 API {@link #MAX_PAGE_SIZE} 限制） */
    public static final int EXPORT_BATCH_SIZE = 500;

    /** 默认群最大人数 */
    public static final int DEFAULT_GROUP_MAX_MEMBER = 200;

    /** 系统通知消息的发送者 ID（虚拟系统账号） */
    public static final Long SYSTEM_USER_ID = 0L;

    /** 内置角色编码 */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";
    /** 审核员：只看板 + 举报处置，不给改用户也不给配角色（后台二期的最小权限示范角色） */
    public static final String ROLE_MODERATOR = "moderator";
    /** 运营：看板 + 用户管理 + 角色分配，但不碰举报处置 */
    public static final String ROLE_OPERATOR = "operator";

    /** 权限点 */
    public static final String PERM_USER_UPDATE = "user:profile:update";
    public static final String PERM_FRIEND_APPLY = "friend:apply";
    public static final String PERM_MESSAGE_SEND = "message:send";
    public static final String PERM_MESSAGE_RECALL_ANY = "message:recall:any";
    public static final String PERM_GROUP_CREATE = "group:create";
    public static final String PERM_FILE_UPLOAD = "file:upload";
    public static final String PERM_SYSTEM_MANAGE = "system:manage";
    /**
     * 后台细粒度权限点（二期）。
     *
     * <p>{@code system:manage} 仍作「全量伞权限」保留：向后兼容——老后台只认这一个串。
     * 二期把四个后台控制器从 {@code @SaCheckPermission(system:manage)} 改细到下面四个点，
     * 于是「审核员只能处置举报、运营只能管人」这类最小权限成为可能。
     * 代价是 admin 角色必须**同时被补齐这四个点**（见 {@code sql/im_data.sql} 种子），
     * 否则改了控制器的权限点、原有的全权管理员反而会被新权限点拦下。
     */
    public static final String PERM_SYSTEM_DASHBOARD = "system:dashboard";
    public static final String PERM_SYSTEM_USER_MANAGE = "system:user:manage";
    public static final String PERM_SYSTEM_REPORT_HANDLE = "system:report:handle";
    public static final String PERM_SYSTEM_ROLE_MANAGE = "system:role:manage";

    /**
     * 三级缓存 key 前缀：拼业务主键，如 {@code user:brief:123}。
     *
     * <p>读方（UserQuerySpiImpl / GroupSpiImpl）与失效方（UserServiceImpl /
     * GroupServiceImpl）分布在不同类里，key 格式必须收口在这里引用，
     * 任何一处手打错前缀都会表现为「改了资料但缓存永远不失效」。
     */
    public static final String CACHE_USER_BRIEF_PREFIX = "user:brief:";
    public static final String CACHE_GROUP_BRIEF_PREFIX = "group:brief:";

    /**
     * 钱包账本流水类型：写进 {@code im_wallet_transaction.type}。
     *
     * <p>充值/送礼/签到/后台调整四类，扣款侧与入账侧分布在不同模块（im-live 送礼、im-user 充值签到），
     * 类型串必须收口在这里统一引用——任何一处手打错，账本里就会出现一条归类错乱的流水。
     *
     * <p>{@code transfer}/{@code packet}/{@code refund} 是用户之间转移积分的三个面：
     * 转账与发红包各产生一条负额行（付款人），收款/领取产生正额行；{@code refund} 只用于
     * 过期红包把没人领的余额退给发送人。注意后三类的 {@code ref_id} 语义不是房间/送礼记录 ID：
     * {@code transfer} 存「对方用户 ID」，{@code packet}/{@code refund} 存「红包 ID」——
     * 后者是「过期退款可从账本倒推」的依据（同一个 packetId 下的行求和即为未退余额）。
     */
    public static final String WALLET_TX_RECHARGE = "recharge";
    public static final String WALLET_TX_GIFT = "gift";
    public static final String WALLET_TX_CHECKIN = "checkin";
    public static final String WALLET_TX_ADJUST = "adjust";
    public static final String WALLET_TX_TRANSFER = "transfer";
    public static final String WALLET_TX_PACKET = "packet";
    public static final String WALLET_TX_PACKET_REFUND = "refund";
    /**
     * 付费直播入场费：{@code live_pay}。只产生一条负额行（扣观众），ref_id 存 roomId。
     * 与 {@code gift} 同口径——模拟积分、<b>不</b>给主播入账、不可提现，仅作为「为看这场而花出去的积分」
     * 记在观众自己的流水里。新增一个类型串无需改表（type 是自由 varchar）。
     */
    public static final String WALLET_TX_LIVE_PAY = "live_pay";

    /**
     * 敏感字段名关键词：字段名转小写后包含任一关键词，其值就不允许明文进日志。
     *
     * <p>用子串匹配而不是精确匹配，是为了覆盖 oldPassword / newPassword / accessToken
     * 这类变体，新增字段时也不用记得来改这里。
     *
     * <p>放在这里而不是留在某个切面里：目前有两处要打码——{@code WebLogAspect} 打的是
     * 请求/响应体的字段名，{@code SlowSqlInterceptor} 打的是 SQL 的占位符属性名，
     * 两边都得认得同一批凭证。各留一份迟早会漂移，而漂移的后果是密码明文落进日志文件。
     */
    public static final List<String> SENSITIVE_KEY_WORDS = List.of(
            "password", "passwd", "token", "secret", "captcha", "smscode",
            "verifycode", "credential", "ticket", "debugcode");
}
