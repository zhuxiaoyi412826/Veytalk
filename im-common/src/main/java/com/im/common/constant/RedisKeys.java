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

    public static String liveOnline(Long roomId) {
        return LIVE_ONLINE + roomId;
    }

    public static String liveHeartbeat(Long roomId) {
        return LIVE_HEARTBEAT + roomId;
    }
}
