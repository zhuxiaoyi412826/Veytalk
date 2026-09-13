package com.im.common.api;

/**
 * 全局响应码。
 *
 * <p>分段规则：1xxx 通用、2xxx 用户、3xxx 好友、4xxx 会话、5xxx 消息、6xxx 群组、7xxx 文件、8xxx 长连接。
 */
public enum ResultCode {

    /* ==================== 1xxx 通用 ==================== */
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(1001, "请求参数有误：{}"),
    UNAUTHORIZED(1002, "登录状态已失效，请重新登录"),
    FORBIDDEN(1003, "没有操作权限"),
    NOT_FOUND(1004, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(1005, "请求方法不被支持"),
    BUSINESS_ERROR(1006, "业务处理失败：{}"),
    SYSTEM_ERROR(1007, "系统繁忙，请稍后重试"),
    VALIDATE_FAILED(1008, "参数校验失败：{}"),
    UPLOAD_TOO_LARGE(1009, "上传内容超过大小限制"),

    /* ==================== 2xxx 用户 ==================== */
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_ALREADY_EXISTS(2002, "账号已存在"),
    USER_PHONE_EXISTS(2003, "手机号已被注册"),
    USER_PASSWORD_ERROR(2004, "账号或密码错误"),
    USER_DISABLED(2005, "账号已被禁用"),
    USER_OLD_PASSWORD_ERROR(2006, "原密码不正确"),
    CAPTCHA_ERROR(2007, "图形验证码错误或已过期"),
    SMS_CODE_ERROR(2008, "短信验证码错误或已过期"),
    SMS_SEND_TOO_FREQUENT(2009, "短信发送过于频繁，请稍后再试"),
    USER_KICKED_OUT(2010, "账号已在其他设备登录"),

    /* ==================== 3xxx 好友 ==================== */
    FRIEND_NOT_FOUND(3001, "好友关系不存在"),
    FRIEND_ALREADY_EXISTS(3002, "你们已经是好友了"),
    FRIEND_APPLY_SELF(3003, "不能添加自己为好友"),
    FRIEND_APPLY_DUPLICATE(3004, "已发送过好友申请，请等待对方处理"),
    FRIEND_APPLY_NOT_FOUND(3005, "好友申请不存在或已处理"),
    FRIEND_BLOCKED(3006, "对方已将你加入黑名单"),
    FRIEND_BLOCKED_BY_ME(3007, "你已将对方加入黑名单，请先解除拉黑"),
    FRIEND_ALREADY_BLOCKED(3008, "已在黑名单中"),

    /* ==================== 4xxx 会话 ==================== */
    CONVERSATION_NOT_FOUND(4001, "会话不存在"),
    CONVERSATION_NO_PERMISSION(4002, "无权访问该会话"),
    CONVERSATION_CREATE_FAILED(4003, "会话创建失败"),

    /* ==================== 5xxx 消息 ==================== */
    MESSAGE_NOT_FOUND(5001, "消息不存在"),
    MESSAGE_DUPLICATE(5002, "消息重复提交"),
    MESSAGE_RECALL_TIMEOUT(5003, "超过 {} 秒的消息不允许撤回"),
    MESSAGE_RECALL_FORBIDDEN(5004, "只能撤回自己发送的消息"),
    MESSAGE_CONTENT_ILLEGAL(5005, "消息内容不合法"),
    MESSAGE_TYPE_UNSUPPORTED(5006, "不支持的消息类型"),
    MESSAGE_SEND_FORBIDDEN(5007, "当前状态下无法发送消息"),

    /* ==================== 6xxx 群组 ==================== */
    GROUP_NOT_FOUND(6001, "群组不存在"),
    GROUP_NOT_MEMBER(6002, "你还不是该群成员"),
    GROUP_ALREADY_MEMBER(6003, "该用户已是群成员"),
    GROUP_PERMISSION_DENIED(6004, "群管理权限不足"),
    GROUP_OWNER_CANNOT_QUIT(6005, "群主不能直接退群，请先转让群主"),
    GROUP_MEMBER_FULL(6006, "群成员已达上限"),
    GROUP_MUTED(6007, "你已被禁言"),
    GROUP_MUTED_ALL(6008, "群主已开启全员禁言"),
    GROUP_DISMISSED(6009, "群组已解散"),

    /* ==================== 7xxx 文件 ==================== */
    FILE_NOT_FOUND(7001, "文件不存在"),
    FILE_TYPE_NOT_ALLOWED(7002, "不支持的文件类型：{}"),
    FILE_EMPTY(7003, "上传文件不能为空"),
    FILE_UPLOAD_FAILED(7004, "文件上传失败"),
    FILE_DOWNLOAD_FORBIDDEN(7005, "无权访问该文件"),
    FILE_STORAGE_UNAVAILABLE(7006, "文件存储服务不可用"),

    /* ==================== 8xxx 长连接 ==================== */
    WS_TICKET_INVALID(8001, "连接票据无效或已过期"),
    WS_SESSION_NOT_FOUND(8002, "连接不存在");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 用参数替换消息模板中的 {} 占位符。
     */
    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        String result = message;
        for (Object arg : args) {
            int idx = result.indexOf("{}");
            if (idx < 0) {
                break;
            }
            result = result.substring(0, idx) + arg + result.substring(idx + 2);
        }
        return result;
    }
}
