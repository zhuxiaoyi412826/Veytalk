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

    /** 短信验证码：im:captcha:sms:{phone} -> 6 位验证码 */
    public static final String CAPTCHA_SMS = PREFIX + "captcha:sms:";

    /** 短信发送频率限制：im:captcha:sms:limit:{phone} */
    public static final String CAPTCHA_SMS_LIMIT = PREFIX + "captcha:sms:limit:";

    /** 邮箱验证码：im:captcha:email:{email} -> 6 位验证码 */
    public static final String CAPTCHA_EMAIL = PREFIX + "captcha:email:";

    /** 邮箱验证码发送频率限制：im:captcha:email:limit:{email} */
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

    public static String captchaImage(String captchaKey) {
        return CAPTCHA_IMAGE + captchaKey;
    }

    public static String captchaSms(String phone) {
        return CAPTCHA_SMS + phone;
    }

    public static String captchaSmsLimit(String phone) {
        return CAPTCHA_SMS_LIMIT + phone;
    }

    public static String captchaEmail(String email) {
        return CAPTCHA_EMAIL + email;
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
}
