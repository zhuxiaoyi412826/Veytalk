package com.im.common.api;

/**
 * 全局响应码。
 *
 * <p>分段规则：1xxx 通用、2xxx 用户、3xxx 好友、4xxx 会话、5xxx 消息、6xxx 群组、7xxx 文件、8xxx 长连接、9xxx 远程控制、10xxx AI 面试、11xxx 直播、12xxx 音视频通话（含群通话）、13xxx 钱包与礼物、14xxx 管理后台与举报。
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
    TOO_MANY_REQUESTS(1010, "操作过于频繁，请稍后再试"),

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
    USER_EMAIL_EXISTS(2011, "邮箱已被注册"),
    USER_PASSWORD_NOT_SET(2012, "该账号尚未设置密码，请用短信或邮箱验证码登录后在「我的」中设置"),
    EMAIL_CODE_ERROR(2013, "邮箱验证码错误或已过期"),
    EMAIL_SEND_TOO_FREQUENT(2014, "邮件发送过于频繁，请稍后再试"),
    MAIL_NOT_CONFIGURED(2015, "邮件服务未配置，无法发送验证码"),
    MAIL_SEND_FAILED(2016, "验证码邮件发送失败，请稍后再试"),
    /* 找回密码专用：与 USER_NOT_FOUND 区分开，前端能直接提示「先去注册」而不是「用户不存在」 */
    USER_PHONE_NOT_BOUND(2017, "该手机号未绑定任何账号，请先注册或用其他方式登录"),
    USER_EMAIL_NOT_BOUND(2018, "该邮箱未绑定任何账号，请先注册或用其他方式登录"),
    PASSWORD_RESET_NEW_SAME(2019, "新密码不能与当前密码相同"),
    SMS_SEND_FAILED(2020, "短信发送失败，请稍后再试"),

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
    MESSAGE_RECALL_TIMEOUT(5003, "已经超出2分钟，无法撤回"),
    MESSAGE_RECALL_FORBIDDEN(5004, "只能撤回自己发送的消息"),
    MESSAGE_CONTENT_ILLEGAL(5005, "消息内容不合法"),
    MESSAGE_TYPE_UNSUPPORTED(5006, "不支持的消息类型"),
    MESSAGE_SEND_FORBIDDEN(5007, "当前状态下无法发送消息"),
    MESSAGE_QUOTE_INVALID(5008, "引用的消息不存在、已撤回或不属于当前会话"),
    MESSAGE_FORWARD_FORBIDDEN(5009, "无权转发该消息"),
    MESSAGE_EDIT_TIMEOUT(5010, "该消息已超过可编辑时限"),
    MESSAGE_EDIT_FORBIDDEN(5011, "只能编辑自己发送的文本消息"),
    /* 语音转文字 / 翻译：依赖外部 ASR/翻译服务，未接入时优雅降级到这两个码（与 LIVE_NOT_CONFIGURED、MAIL_NOT_CONFIGURED 同构） */
    MESSAGE_TO_TEXT_UNSUPPORTED(5012, "只有未撤回的语音消息支持转文字"),
    MESSAGE_TRANSLATE_UNSUPPORTED(5013, "只有未撤回的文本消息支持翻译"),
    MESSAGE_TO_TEXT_UNAVAILABLE(5014, "语音转文字服务未配置或暂不可用"),
    MESSAGE_TRANSLATE_UNAVAILABLE(5015, "消息翻译服务未配置或暂不可用"),

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
    GROUP_ROBOT_NOT_FOUND(6010, "群机器人不存在或已被移除"),
    GROUP_ROBOT_DISABLED(6011, "该群机器人已被停用"),

    /* ==================== 7xxx 文件 ==================== */
    FILE_NOT_FOUND(7001, "文件不存在"),
    FILE_TYPE_NOT_ALLOWED(7002, "不支持的文件类型：{}"),
    FILE_EMPTY(7003, "上传文件不能为空"),
    FILE_UPLOAD_FAILED(7004, "文件上传失败"),
    FILE_DOWNLOAD_FORBIDDEN(7005, "无权访问该文件"),
    FILE_STORAGE_UNAVAILABLE(7006, "文件存储服务不可用"),
    FILE_UPLOAD_SESSION_NOT_FOUND(7007, "上传会话不存在或已过期，请重新上传"),
    FILE_CHUNK_INVALID(7008, "分片校验失败：{}"),
    FILE_MD5_MISMATCH(7009, "文件内容校验失败，请重新上传"),
    /* 图片内容审核命中：前端识别出违规后拒发，用这个码统一文案口径 */
    FILE_CONTENT_UNSAFE(7010, "图片含违规内容，已拒发"),

    /* ==================== 8xxx 长连接 ==================== */
    WS_TICKET_INVALID(8001, "连接票据无效或已过期"),
    WS_SESSION_NOT_FOUND(8002, "连接不存在"),

    /* ==================== 9xxx 远程控制 ==================== */
    REMOTE_DEVICE_NOT_FOUND(9001, "设备不存在"),
    REMOTE_DEVICE_OFFLINE(9002, "设备不在线，无法发起远程"),
    REMOTE_DEVICE_BUSY(9003, "设备正被其他会话占用"),
    REMOTE_DEVICE_REFUSED(9004, "设备已设置拒绝接入"),
    REMOTE_SESSION_NOT_FOUND(9005, "远程会话不存在或已结束"),
    REMOTE_SESSION_FORBIDDEN(9006, "无权访问该远程会话"),
    REMOTE_TICKET_INVALID(9007, "远程连接票据无效、已使用或已过期"),
    REMOTE_READONLY(9008, "当前会话为只读模式，禁止输入操作"),
    REMOTE_FRAME_TOO_LARGE(9009, "中继帧超过大小限制"),

    /* ==================== 10xxx AI 面试监考 ==================== */
    /* 独立于 9xxx：远程控制与面试监考是两个不相干的域，混在一个千位段里，
       排查时看到 9xxx 还得先猜是哪块业务 */
    AI_INTERVIEW_NOT_FOUND(10001, "面试会话不存在或已过期"),
    AI_INTERVIEW_FORBIDDEN(10002, "无权访问该面试会话"),
    AI_INTERVIEW_ENDED(10003, "面试已结束，不能再上报该会话的数据"),
    /* 语音链路三码：配置缺失与调用失败分开，前端据此决定是「置灰入口」还是「提示重试」 */
    AI_AUDIO_DISABLED(10004, "语音服务未启用，请配置 ALI_BABA_API_KEY 后重启服务"),
    AI_ASR_FAILED(10005, "语音转文字失败，请重试"),
    AI_TTS_FAILED(10006, "语音合成失败，请重试或切换系统音色"),

    /* ==================== 11xxx 直播 ==================== */
    LIVE_DISABLED(11001, "直播功能未开启"),
    LIVE_NOT_CONFIGURED(11002, "直播推流/播放地址未配置，无法开播"),
    LIVE_ROOM_NOT_FOUND(11003, "直播间不存在"),
    LIVE_NOT_LIVING(11004, "该直播已结束"),
    LIVE_FORBIDDEN(11005, "无权操作该直播间"),
    LIVE_ALREADY_LIVING(11006, "你已有正在进行的直播，请先关播"),
    /* 连麦：主播邀请观众上麦，放宽好友校验但仍需登录且为本场观众 */
    LIVE_LINK_FULL(11007, "连麦位已满，先请一位下麦再试"),
    LIVE_LINK_NOT_FOUND(11008, "连麦申请不存在或已结束"),
    LIVE_LINK_FORBIDDEN(11009, "无权操作该连麦"),
    LIVE_LINK_DISABLED(11010, "连麦功能未开启"),
    /* 弹幕房管：禁言名单与屏蔽词都只存 Redis（无表），文案在服务端定口径，REST 报错与弹幕 error 帧共用同一段 */
    LIVE_USER_MUTED(11011, "你已被主播禁言"),
    LIVE_MUTE_SELF(11012, "不能禁言主播自己"),
    /* 连麦 PK：会话态只存 Redis（无表），与连麦态同构——只在两个房间同时直播时有意义 */
    LIVE_PK_NOT_FOUND(11013, "PK 不存在或已结束"),
    LIVE_PK_RUNNING(11014, "你已在一场 PK 中，请先结束当前对局"),
    LIVE_PK_OPPONENT_NOT_LIVING(11015, "对方当前没在直播，无法开始 PK"),
    LIVE_PK_DISABLED(11016, "PK 功能未开启"),
    /* 直播回放存档：元数据只存 Redis（无表），画面字节本来就在媒体服务器上，见 md/直播.md §15 */
    LIVE_REPLAY_DISABLED(11017, "回放功能未开启"),
    LIVE_REPLAY_NOT_FOUND(11018, "该场直播没有可用的回放"),
    /* 付费 / 密码直播：门禁态只存 Redis（无表），与 PK / 房管 / 回放同构，见 md/直播.md §17 */
    LIVE_GATE_PASSWORD_WRONG(11019, "房间密码错误"),
    LIVE_GATE_NOT_PASSWORD(11020, "该房间不是密码直播"),
    LIVE_GATE_NOT_PAID(11021, "该房间不是付费直播"),
    LIVE_GATE_PRICE_INVALID(11022, "房间价格不合法"),

    /* ==================== 12xxx 音视频通话 ==================== */
    CALL_DISABLED(12001, "通话功能未开启"),
    CALL_PEER_OFFLINE(12002, "对方不在线，无法发起通话"),
    CALL_BUSY(12003, "对方正忙，请稍后再试"),
    CALL_NOT_FRIEND(12004, "只能与好友发起通话"),
    CALL_NOT_FOUND(12005, "通话不存在或已结束"),
    CALL_FORBIDDEN(12006, "无权访问该通话"),
    CALL_RECORD_TOO_LARGE(12007, "通话录制文件过大，未能上传"),
    /* 群通话（多人）三个码：不复用 12001~12006，因为同一个失败在 1:1 与多人场景下
     * 该给用户的文案不同（「对方正忙」对四个人同时在等的群通话是错的话） */
    GROUP_CALL_DISABLED(12008, "群通话功能未开启"),
    GROUP_CALL_FULL(12009, "群通话人数已满"),
    GROUP_CALL_NOT_MEMBER(12010, "只有本群成员才能发起或加入群通话"),

    /* ==================== 13xxx 钱包与礼物 ==================== */
    /* 虚拟积分/币体系：不涉真实货币，充值走模拟支付桩（预留 PaymentSpi），与 D1 语音转文字降级同构 */
    WALLET_DISABLED(13001, "钱包功能未开启"),
    WALLET_BALANCE_NOT_ENOUGH(13002, "积分余额不足，请先充值或每日签到领取"),
    GIFT_NOT_FOUND(13003, "礼物不存在"),
    GIFT_DISABLED(13004, "该礼物已下架"),
    GIFT_AMOUNT_INVALID(13005, "送礼数量不合法"),
    RECHARGE_AMOUNT_INVALID(13006, "充值数额超出允许范围"),
    PAYMENT_UNAVAILABLE(13007, "支付服务未配置，暂时无法充值"),
    CHECKIN_ALREADY_DONE(13008, "今日已签到，明天再来吧"),
    GIFT_SELF_FORBIDDEN(13009, "不能给自己的直播送礼物"),

    /* 用户之间转移积分：转账（1301x）与红包（1302x）。两者都是「先动钱、后发消息」，
       所以校验全在钱包侧跑完，消息侧只定格展示字段 */
    TRANSFER_AMOUNT_INVALID(13010, "转账积分超出允许范围"),
    TRANSFER_SELF_FORBIDDEN(13011, "不能给自己转账"),
    TRANSFER_PEER_NOT_FOUND(13012, "收款人不存在"),
    TRANSFER_PEER_NOT_FRIEND(13013, "只能给好友转账"),
    TRANSFER_SINGLE_ONLY(13014, "转账仅支持单聊，群聊里请用红包"),
    PACKET_AMOUNT_INVALID(13020, "红包积分超出允许范围"),
    PACKET_COUNT_INVALID(13021, "红包份数不合法"),
    PACKET_NOT_FOUND(13022, "红包不存在或已过期退回"),
    PACKET_NOT_MEMBER(13023, "只有本会话成员能领取这个红包"),
    PACKET_SELF_CLAIM_FORBIDDEN(13024, "不能领取自己发出的红包"),
    PACKET_ALREADY_CLAIMED(13025, "你已经领过这个红包了"),
    PACKET_EMPTY(13026, "红包已被领完"),
    MONEY_MESSAGE_FORBIDDEN(13027, "转账与红包消息发出后不能撑回或转发"),
    
    /* ==================== 14xxx 举报与管理后台 ==================== */
    /* 举报是面对全体用户的入口，所以错误文案直接写给举报人看（能回 14xxx 的都是业务拒绝，不是系统错）；
       14005~14008 是后台侧的，只会在管理页面上出现；14009 虽然由运维开关触发，但拒绝的是普通用户的提交，
       文案要能被举报人读懂 */
    REPORT_TARGET_NOT_FOUND(14001, "被举报的对象不存在或已被删除"),
    REPORT_SELF_FORBIDDEN(14002, "不能举报自己"),
    REPORT_DUPLICATED(14003, "你已经举报过这个对象了，请等处理结果"),
    REPORT_QUOTA_EXCEEDED(14004, "今日举报次数已用完，请明天再来"),
    REPORT_NOT_FOUND(14005, "举报工单不存在"),
    REPORT_ALREADY_HANDLED(14006, "该举报已处理，不能重复处置"),
    ADMIN_SELF_FORBIDDEN(14007, "不能对自己执行该管理操作（封禁自己会把自己锁在门外）"),
    ADMIN_DISPOSITION_UNAVAILABLE(14008, "该处置动作需要的能力未装配（如直播模块未启用）"),
    REPORT_DISABLED(14009, "举报功能当前未开启");

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
