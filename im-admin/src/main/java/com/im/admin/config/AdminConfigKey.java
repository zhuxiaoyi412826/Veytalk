package com.im.admin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台可以改的运行期配置<b>白名单</b>（管理后台四期 · 第四批）。
 *
 * <h2>为什么必须是一张白名单，而不是「任意 im.* 都能改」</h2>
 *
 * <p>把配置入库的诱惑在于通用：给个 key 就能改任意属性，后台立刻拥有全站。这条路被堵死是因为
 * 它会把 {@code spring.datasource.password}、{@code sa-token.jwt-secret-key} 这类东西也变成
 * 「一个后台账号一句请求」就能改的对象——那等于把整个系统的钥匙挂在了后台页面上。
 * 所以这里的每一项都是<b>点名加进来的</b>：只有本枚举里的 key 能被写，其余一律回
 * {@code ADMIN_CONFIG_KEY_UNKNOWN}；而且写入的目标是 {@code ImProperties} 那个单例的字段
 * （或敏感词过滤器的内存标志），<b>不碰 Spring 的 {@code Environment}</b>——
 * 改 Environment 会牵动 {@code @ConfigurationProperties} 的重新绑定，那是上面那把锁的来源。
 *
 * <h2>为什么标题、说明、取值范围都写死在代码里</h2>
 *
 * <p>{@code im_config} 表只存「谁把哪个 key 改成了什么值」，其余元数据全在本枚举。
 * 库里再存一份标题和说明，就会有两份真相：改了代码里的文案，库里的老行不会跟着变，
 * 界面上于是长期挂着一句和实际行为不符的解释。取值范围同理——它决定了服务端拒绝什么，
 * 必须和校验代码在一起，不能是「一行数据」。
 *
 * <h2>加一项要做三件事</h2>
 *
 * <p>① 在这里加常量；② 在 {@code RuntimeConfigService} 的 read 与 write 两个 switch
 * <b>表达式</b>里各补一个分支——它们是 switch 表达式而不是语句，缺一个分支就编译不过，
 * 所以不存在「加了目录但改了不生效」这种最容易漏的一半；③ 确认目标字段是<b>每次调用都读</b>的，
 * 而不是在启动时被抄进了某个 final 字段（那样改库不会有任何效果，界面会骗人）。
 */
public enum AdminConfigKey {

    /* ==================== 内容过滤 ==================== */

    /** 唯一不走 {@code ImProperties} 的一项：它的运行态在过滤器的内存标志上 */
    SENSITIVE_FILTER_ENABLED("message.sensitive-filter-enabled", Group.SENSITIVE, Type.BOOLEAN,
            "敏感词过滤开关",
            "命中词库的文本与文件名在落库和投递前遮成 *（是遮蔽不是拒发）。关掉立刻全服不遮，"
                    + "已经在库里的 * 不会还原。对管理员自己发的内容同样生效。",
            null, null),

    /* ==================== 消息 ==================== */

    MESSAGE_RECALL_LIMIT_SECONDS("message.recall-limit-seconds", Group.MESSAGE, Type.NUMBER,
            "撤回与编辑时限（秒）",
            "普通用户能撤回、编辑自己消息的时间窗。改小只影响之后的操作，已经过窗的仍然不能撤。",
            5L, 3600L),
    MESSAGE_MAX_TEXT_LENGTH("message.max-text-length", Group.MESSAGE, Type.NUMBER,
            "文本消息最大长度（字符）",
            "超过这个长度的文本会被截断。调大要同时看 WebSocket 单帧上限（默认 64KB），"
                    + "中文一帧 3 字节，单改这一项会让长消息撞上帧限制。",
            100L, 20000L),
    MESSAGE_RETENTION_DAYS("message.retention-days", Group.MESSAGE, Type.NUMBER,
            "消息保留天数（0 为永不清理）",
            "清理任务按这个天数物理删除历史消息。<b>从 0 改成正数是不可逆的</b>：一旦跑起来，"
                    + "超期的消息就真没了，改回 0 也不会把它们找回来。",
            0L, 3650L),

    /* ==================== 缓存 ==================== */

    CACHE_ENABLED("cache.enabled", Group.CACHE, Type.BOOLEAN,
            "三级缓存总开关",
            "关掉后所有读直接回源 MySQL。排障时用它确认「是不是缓存脏了」，"
                    + "代价是热点读全部压到数据库上，长时间开着会把库拖慢。",
            null, null),

    /* ==================== 限流 ==================== */

    RATE_LIMIT_ENABLED("rate-limit.enabled", Group.RATE_LIMIT, Type.BOOLEAN,
            "接口限流总开关",
            "关掉后注解配额与全局兜底全部失效，只剩 WebSocket 通道那两层。"
                    + "只在压测或排障时临时关，开着等于没有防刷。",
            null, null),
    RATE_LIMIT_GLOBAL_COUNT("rate-limit.global-count", Group.RATE_LIMIT, Type.NUMBER,
            "全局兜底：单 IP 窗口内请求数",
            "覆盖所有未单独标注解的接口。内网多人共用一个出口 IP 时按人数上调，"
                    + "否则整个办公室会互相挤兑同一个配额。",
            10L, 100000L),
    RATE_LIMIT_GLOBAL_SECONDS("rate-limit.global-seconds", Group.RATE_LIMIT, Type.NUMBER,
            "全局兜底的窗口长度（秒）",
            "与上面那项组成「N 次 / M 秒」。只调其中一个会同时改变配额和它的突发容忍度。",
            1L, 600L),
    RATE_LIMIT_WS_CHAT_COUNT("rate-limit.ws-chat-count", Group.RATE_LIMIT, Type.NUMBER,
            "单用户发消息频率（条）",
            "超限的聊天帧被服务端以 error 帧拒绝但不掉线，用户看到的是「发不出去」。",
            1L, 10000L),
    RATE_LIMIT_WS_CHAT_SECONDS("rate-limit.ws-chat-seconds", Group.RATE_LIMIT, Type.NUMBER,
            "单用户发消息频率的窗口（秒）",
            "与上一条组成「M 秒内最多 N 条」。",
            1L, 600L),

    /* ==================== 图片审核 ==================== */

    MODERATION_ENABLED("moderation.enabled", Group.MODERATION, Type.BOOLEAN,
            "图片内容审核总开关",
            "关掉后策略接口回 enabled=false，前端整个识别环节直接跳过。"
                    + "注意这套判定跑在发送端浏览器里，本来就拦不住刻意绕过的人，"
                    + "关掉它等于连「正常路径下的违规图」也不拦了。",
            null, null),
    MODERATION_AUDIT_ENABLED("moderation.audit-enabled", Group.MODERATION, Type.BOOLEAN,
            "审核命中后是否上报审计",
            "关掉仍然拒发，只是不再往审计通道写条目——出问题时就没有「谁传过什么」可查。",
            null, null),

    /* ==================== 智能能力 ==================== */

    INTELLIGENCE_ENABLED("intelligence.enabled", Group.INTELLIGENCE, Type.BOOLEAN,
            "语音转文字与翻译入口",
            "关掉后前端隐藏这两个菜单，服务端也不再去找对应的 SPI。"
                    + "本期没有接真实厂商，开着也只是走内置桩。",
            null, null),

    /* ==================== 音视频通话 ==================== */

    CALL_ENABLED("call.enabled", Group.CALL, Type.BOOLEAN,
            "音视频通话总开关",
            "关掉后 /api/call/config 回 enabled=false，前端通话入口整体禁用，"
                    + "正在进行的通话由服务端下发 hangup 兜底收尾。群通话与连麦都挂在它下面。",
            null, null),
    CALL_GROUP_ENABLED("call.group-enabled", Group.CALL, Type.BOOLEAN,
            "群通话子开关",
            "只关多人、不关一对一。出现「群通话把上行带宽吃满」这类问题时用它的这一刀，"
                    + "不必连好友通话一起停掉。",
            null, null),
    CALL_GROUP_MAX_SLOTS("call.group-max-slots", Group.CALL, Type.NUMBER,
            "群通话同时在场人数",
            "群通话是 mesh（每两人一路连接，不经服务器转发媒体），N 人时每人上行 N-1 路。"
                    + "服务端把它夹在 [2,6]：6 人以上得换 SFU，不是调这个数字能解决的。",
            2L, 6L),
    CALL_RING_TIMEOUT_SECONDS("call.ring-timeout-seconds", Group.CALL, Type.NUMBER,
            "振铃超时（秒）",
            "被叫在这段时间内没接，服务端主动下发 timeout 并结束这次呼叫。太长会让被叫方"
                    + "一直被打扰，太短则来不及掏手机。",
            5L, 300L),
    CALL_MAX_DURATION_SECONDS("call.max-duration-seconds", Group.CALL, Type.NUMBER,
            "单通话最长时长（秒）",
            "防御性上限，超时由服务端下发 hangup 兜底。真正限制时长的是出口带宽，不是这个数字。",
            60L, 86400L),

    /* ==================== 钱包与礼物 ==================== */

    WALLET_ENABLED("wallet.enabled", Group.WALLET, Type.BOOLEAN,
            "钱包与礼物总开关",
            "关掉后送礼、充值、签到、转账、红包一律回 WALLET_DISABLED，前端礼簿隐藏。"
                    + "已经发出去的积分不会回收，只是不能再动。",
            null, null),
    WALLET_TRANSFER_ENABLED("wallet.transfer-enabled", Group.WALLET, Type.BOOLEAN,
            "用户之间转积分（转账与红包）",
            "与上一个旋钮分开的理由：送礼是向平台买服务，转账是用户之间倒腾积分，"
                    + "后者可能被用来对倒刷量、变相赌博。线上想收紧时可以只关这一项而保留送礼与充值。",
            null, null),
    WALLET_CHECKIN_BONUS("wallet.checkin-bonus", Group.WALLET, Type.NUMBER,
            "每日签到赠送积分",
            "签到是积分的主要免费来源，调高会直接稀释「充值得到的积分」的含金量。",
            0L, 100000L),
    WALLET_MAX_GIFT_COUNT("wallet.max-gift-count", Group.WALLET, Type.NUMBER,
            "单次送礼数量上限",
            "夹住前端传入的 count 防刷。这是单笔上限，不是单场上限。",
            1L, 999L),
    WALLET_TRANSFER_MAX("wallet.transfer-max", Group.WALLET, Type.NUMBER,
            "单笔转账积分上限",
            "与 {@code wallet.transfer-min}（目前不在本目录里，仍是配置文件项）要成对看："
                    + "只调上界而让下界超过它，转账会整体失败而不是自动收窄。",
            1L, 1000000L),
    WALLET_PACKET_MAX("wallet.packet-max", Group.WALLET, Type.NUMBER,
            "单个红包总额上限",
            "份数另有上限，且总额必须不小于份数（保证每份至少 1 积分）。",
            1L, 1000000L),
    WALLET_PACKET_EXPIRE_HOURS("wallet.packet-expire-hours", Group.WALLET, Type.NUMBER,
            "红包无人领取的保留时长（小时）",
            "它同时是退款扫描的判定口径（按流水 create_time 倒推）。改动会让<b>存量在飞红包</b>"
                    + "的到期时间一起变，所以它是运维旋钮，不能拿它做对账依据。",
            1L, 168L),

    /* ==================== 直播连麦 ==================== */

    LINKMIC_ENABLED("linkmic.enabled", Group.LINKMIC, Type.BOOLEAN,
            "直播连麦总开关",
            "关掉后 REST 回 enabled=false、WS 信令静默丢弃，观众点上麦不会有反应。"
                    + "已经在麦上的嘉宾由服务端下发 leave 收尾。",
            null, null),
    LINKMIC_MAX_SLOTS("linkmic.max-slots", Group.LINKMIC, Type.NUMBER,
            "单房间同时在麦嘉宾数",
            "不含主播。超限回 LIVE_LINK_FULL。每一路麦都是观众端的一路上行，"
                    + "调大之前先确认主播那边的带宽。",
            1L, 16L);

    /** 值类型：决定服务端怎么校验这一串字符，也决定前端用开关还是数字框 */
    public enum Type {
        /** 只能是 {@code true} / {@code false} 两个字面量 */
        BOOLEAN,
        /** 非负或可负的整数，必须落在 {@code AdminConfigKey} 声明的 [min, max] 内 */
        NUMBER
    }

    /**
     * 分组：只服务后台页面的分区展示与排序，不参与任何判定。
     *
     * <p>code 用驼峰而不是下划线，是为了和 {@code im.*} 的属性段写法一致（{@code rateLimit}、
     * {@code linkmic}），页面上一眼能看出这一组对应配置文件里的哪一段。
     */
    public enum Group {
        SENSITIVE("sensitive", "内容过滤"),
        MESSAGE("message", "消息"),
        CACHE("cache", "缓存"),
        RATE_LIMIT("rateLimit", "限流"),
        MODERATION("moderation", "图片审核"),
        INTELLIGENCE("intelligence", "智能能力"),
        CALL("call", "音视频通话"),
        WALLET("wallet", "钱包与礼物"),
        LINKMIC("linkmic", "直播连麦");

        private final String code;
        private final String label;

        Group(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }
    }

    private final String key;
    private final Group group;
    private final Type type;
    private final String title;
    private final String description;
    private final Long min;
    private final Long max;

    AdminConfigKey(String key, Group group, Type type, String title, String description, Long min, Long max) {
        this.key = key;
        this.group = group;
        this.type = type;
        this.title = title;
        this.description = description;
        this.min = min;
        this.max = max;
    }

    public String key() {
        return key;
    }

    public Group group() {
        return group;
    }

    public Type type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Long min() {
        return min;
    }

    public Long max() {
        return max;
    }

    /** 布尔项的可选项，前端渲染下拉时用，省得它在界面上自己拼一份 */
    public static List<String> booleanOptions() {
        return List.of("true", "false");
    }

    /**
     * 按 key 找枚举；<b>找不到返回 null 而不是抛异常</b>。
     *
     * <p>调用方有两个方向：写入时必须「null 即拒绝」（这是那道白名单闸门），
     * 而读取启动时库里已有的行时必须「null 就跳过并告警」——否则代码里删掉一个配置项，
     * 老库里残留的那一行会让后端<b>起不来</b>。删功能不该演变成停机事故。
     */
    public static AdminConfigKey of(String key) {
        return key == null ? null : INDEX.get(key);
    }

    /** 按分组返回该组的全部项，顺序就是枚举的声明顺序（页面按这个顺序排，不再自己定义一套） */
    public static List<AdminConfigKey> byGroup(Group group) {
        List<AdminConfigKey> list = new ArrayList<>();
        for (AdminConfigKey item : values()) {
            if (item.group == group) {
                list.add(item);
            }
        }
        return list;
    }

    /** 全部项按声明顺序，分组切分由调用方做 */
    public static List<AdminConfigKey> all() {
        return List.of(values());
    }

    /** key → 枚举。用 Map 而不是每次遍历 values()：白名单判定在每次写入请求上都要跑一次 */
    private static final Map<String, AdminConfigKey> INDEX = new LinkedHashMap<>();

    static {
        for (AdminConfigKey item : values()) {
            INDEX.put(item.key, item);
        }
    }
}
