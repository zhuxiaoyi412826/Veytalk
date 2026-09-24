package com.im.remote.protocol;

/**
 * 远程控制通道协议常量：三端（Agent / 服务端 / 前端）共同遵守的帧格式定义。
 *
 * <h2>文本帧</h2>
 *
 * <p>JSON 信封 {@code {v:1, type, sid, seq, ts, data}}。type 取值见下方常量；
 * 除 ping/ready/ pong 等控制类帧外，其余帧都由中继原样转发给对方，
 * 服务端只看 type 做路由与策略（只读拦截），不改写 data。
 *
 * <h2>二进制帧（屏幕块 / 文件块）</h2>
 *
 * <pre>
 * [1B 帧类型][8B 会话ID][4B 元数据长度][元数据JSON(UTF-8)][载荷]
 * </pre>
 *
 * <p>会话 ID 用 8 字节而不是字符串：它是中继路由的第一关键字，定长前缀让
 * 转发路径上不需要任何解析就能取出。元数据保持明文——加密只覆盖载荷，
 * 因为中继要靠 {@code {x,y,w,h}} / {@code {transferId,index}} 之外的字段做统计与限流，
 * 而块坐标本身不泄露屏幕内容。载荷在 {@code im.remote.aes=true} 时为
 * {@code 12B IV + AES-256-GCM 密文}（WebCrypto 的 tag 拼在密文尾部，Java 侧参数化兼容）。
 */
public final class RemoteProtocol {

    private RemoteProtocol() {
    }

    /* ==================== 文本帧 type ==================== */

    /** Agent → 服务端：声明设备身份（deviceId/deviceName/os），必须在鉴权握手之后第一帧发送 */
    public static final String TYPE_AUTH = "auth";
    /** 任意端 → 服务端：心跳，服务端回 pong，不落转发路径 */
    public static final String TYPE_PING = "ping";
    /** 服务端 → 任意端：心跳应答 */
    public static final String TYPE_PONG = "pong";
    /** 服务端 → Agent：注册成功，携带心跳间隔等运行参数 */
    public static final String TYPE_READY = "ready";
    /** 服务端 → Agent：远程邀请（inviterUserId/nickname/sessionId/permission） */
    public static final String TYPE_INVITE = "invite";
    /** Agent → 服务端：同意邀请，可降档权限；服务端回 invite-accepted 给控制端（REST 响应等不到 WS，走接受帧） */
    public static final String TYPE_ACCEPT = "accept";
    /** Agent → 服务端：拒绝邀请 */
    public static final String TYPE_REJECT = "reject";
    /** 控制端 → 服务端：WS 建立后的首帧，服务端消费一次性 ticket 并双向绑定中继 */
    public static final String TYPE_CONTROL_READY = "control-ready";
    /** 服务端 → 控制端：会话生效（含 aesKey），可以开始请求推屏 */
    public static final String TYPE_SESSION_START = "session-start";
    /** 任意端 → 服务端：结束会话（inviter-end / invitee-end） */
    public static final String TYPE_SESSION_END = "session-end";
    /** 服务端 → 任意端：会话结束通知（带原因） */
    public static final String TYPE_SESSION_CLOSED = "session-closed";

    /** 控制端 → Agent：开始/停止推屏，data 携带 fps/quality 档位 */
    public static final String TYPE_SCREEN_START = "screen-start";
    public static final String TYPE_SCREEN_STOP = "screen-stop";
    /** 控制端 → Agent：鼠标（move/down/up/wheel，归一化坐标 0~10000） */
    public static final String TYPE_MOUSE = "mouse";
    /** 控制端 → Agent：键盘（press/release + keyCode） */
    public static final String TYPE_KEY = "key";

    /** 控制端 → Agent：列目录 */
    public static final String TYPE_LIST_DIR = "list-dir";
    /** 控制端 → Agent：下载（Agent 以二进制文件块分块回传） */
    public static final String TYPE_FILE_GET = "file-get";
    /** 控制端 → Agent：上传（data 带 name/size，随后以二进制文件块分块发送） */
    public static final String TYPE_FILE_PUT = "file-put";
    /** 控制端 → Agent：删除/重命名/建目录（高危，受 Agent 本地开关约束） */
    public static final String TYPE_RM = "rm";
    public static final String TYPE_RENAME = "rename";
    public static final String TYPE_MKDIR = "mkdir";

    /** 控制端 → Agent：进程列表 / 结束进程 / 启动程序 */
    public static final String TYPE_PS_LIST = "ps-list";
    public static final String TYPE_PS_KILL = "ps-kill";
    public static final String TYPE_PS_RUN = "ps-run";
    /** 控制端 → Agent：执行 cmd 命令，输出以 result 帧回传 */
    public static final String TYPE_EXEC = "exec";
    /** 控制端 → Agent：电源指令（shutdown / reboot / lock） */
    public static final String TYPE_POWER = "power";
    /** 任一端 → 对方：剪贴板文本同步 */
    public static final String TYPE_CLIP_SYNC = "clip-sync";
    /** 控制端 → Agent：切换显示器 */
    public static final String TYPE_MONITOR_SWITCH = "monitor-switch";

    /** Agent → 控制端：指令执行结果/错误回执，data.reqSeq 对应发起帧的 seq */
    public static final String TYPE_RESULT = "result";
    /** Agent → 控制端：本地拒绝执行（权限不足 / 开关关闭 / 路径越界） */
    public static final String TYPE_ERROR = "error";
    /** Agent → 服务端：高危操作审计上报（服务端落库，不转发） */
    public static final String TYPE_AUDIT = "audit";

    /* ==================== 二进制帧类型 ==================== */

    /** 屏幕增量块，元数据 {x,y,w,h,screenW,screenH,key} */
    public static final byte FRAME_SCREEN = 1;
    /** 文件分块，元数据 {transferId,name,index,total} */
    public static final byte FRAME_FILE = 2;

    /* ==================== Redis key ==================== */

    /** 控制端一次性连接票据：remote:ticket:{ticket} -> sessionId */
    public static final String REDIS_TICKET_PREFIX = "remote:ticket:";
}
