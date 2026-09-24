package com.im.common.constant;

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

    /** 默认群最大人数 */
    public static final int DEFAULT_GROUP_MAX_MEMBER = 200;

    /** 系统通知消息的发送者 ID（虚拟系统账号） */
    public static final Long SYSTEM_USER_ID = 0L;

    /** 内置角色编码 */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    /** 权限点 */
    public static final String PERM_USER_UPDATE = "user:profile:update";
    public static final String PERM_FRIEND_APPLY = "friend:apply";
    public static final String PERM_MESSAGE_SEND = "message:send";
    public static final String PERM_MESSAGE_RECALL_ANY = "message:recall:any";
    public static final String PERM_GROUP_CREATE = "group:create";
    public static final String PERM_FILE_UPLOAD = "file:upload";
    public static final String PERM_SYSTEM_MANAGE = "system:manage";

    /**
     * 三级缓存 key 前缀：拼业务主键，如 {@code user:brief:123}。
     *
     * <p>读方（UserQuerySpiImpl / GroupSpiImpl）与失效方（UserServiceImpl /
     * GroupServiceImpl）分布在不同类里，key 格式必须收口在这里引用，
     * 任何一处手打错前缀都会表现为「改了资料但缓存永远不失效」。
     */
    public static final String CACHE_USER_BRIEF_PREFIX = "user:brief:";
    public static final String CACHE_GROUP_BRIEF_PREFIX = "group:brief:";
}
