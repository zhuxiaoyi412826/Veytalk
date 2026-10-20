package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import lombok.Getter;

/**
 * 消息类型。
 */
@Getter
public enum MsgType {

    /** 文本消息 */
    TEXT(1, "文本"),
    /** 图片消息 */
    IMAGE(2, "图片"),
    /** 文件消息 */
    FILE(3, "文件"),
    /** 语音消息 */
    VOICE(4, "语音"),
    /** 系统通知，由服务端生成，客户端只读 */
    SYSTEM(5, "系统通知"),
    /**
     * 通话记录：一次语音 / 视频通话结束后落的一条消息，{@code content} 存录音录像文件 ID（可空表示未录制），
     * {@code extra} 带时长、媒体类型与是否录屏。与 {@link #VOICE} 区分：那是语音留言，这是一次实时通话的结算凭证。
     */
    CALL(6, "通话"),
    /**
     * 名片消息：把一个联系人名片转发给当前会话，{@code extra} 快照被分享用户的 ID / 昵称 / 头像，
     * 接收端无需再查库即可渲染，点击可直接打开其资料页。
     */
    CARD(7, "名片"),
    /**
     * 位置消息：发送一个地理坐标 + 文字地址，{@code extra} 带 locationName / latitude / longitude，
     * 前端渲染成地图瓦片缩略图，点击可跳转外部地图。
     */
    LOCATION(8, "位置"),
    /**
     * 合并转发（聊天记录）：把多条消息打包成一条。{@code extra} 带 mergedTitle 与
     * mergedMessages（发送时定格的快照列表），content 存标题；接收端渲染成记录卡片，点击展开查看器。
     */
    MERGED_FORWARD(9, "聊天记录"),
    /**
     * 投票消息：一条带标题与若干选项的投票。{@code extra} 存静态定义（voteTitle / voteOptions /
     * voteMultiple / voteAnonymous / voteClosed），票数由 {@code im_message_vote} 表实时聚合到 VO.vote。
     */
    VOTE(10, "投票"),
    /**
     * 机器人消息：由群机器人 Webhook 端点在服务端内部生成，{@code content} 存文本，
     * {@code extra} 快照机器人名称与头像（发送者并非真实用户，故不走 {@code UserQuerySpi}）。
     * 与 {@link #SYSTEM} 一样仅允许 {@code internal=true} 的路径产生，客户端伪造直接被拒。
     */
    ROBOT(11, "机器人"),
    /**
     * 转账消息：由 im-user 的转账服务在「钱已经动完」之后内部发出，{@code extra} 定格金额 / 收款人 /
     * 附言。与 {@link #SYSTEM}、{@link #ROBOT} 一样只允许 {@code internal=true} 路径产生——
     * 客户端若能伪造这个类型，就等于凭空造出一笔「已到账」的转账。
     */
    TRANSFER(12, "转账"),
    /**
     * 红包消息：同样只允许服务端内部产生。{@code extra} 定格总额 / 份数 / 祝福语与 {@code packetId}，
     * 「谁领了、还剩多少」不属于消息（会一直变），由前端点开时向 {@code /api/wallet/packet/{id}} 现取。
     */
    RED_PACKET(13, "红包"),
    /**
     * 接龙消息：一条会随成员「参与接龙」不断追加条目的消息（报名 / 点单 / 报名统计）。
     * {@code extra} 存主题 {@code chainTitle} 与有序条目列表 {@code chainItems}（每项含序号隐式=下标、
     * 参与者 ID / 昵称快照 / 条目正文 / 时刻）与 {@code chainClosed} 截止标记。
     *
     * <p>与 {@link #VOTE} 的区别：投票的票数是「谁选了哪个既有选项」的聚合，走 {@code im_message_vote} 表；
     * 接龙的条目是「每人自带正文的一行」，行数与内容都不固定，直接存进 {@code extra} 的 JSON 列，
     * 靠服务端 read-modify-write + Redis 短锁串行化追加，<b>零新表</b>。
     */
    CHAIN(14, "接龙");

    @JsonValue
    private final int code;
    private final String desc;

    MsgType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static MsgType of(Integer code) {
        if (code == null) {
            throw new BusinessException(ResultCode.MESSAGE_TYPE_UNSUPPORTED);
        }
        for (MsgType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new BusinessException(ResultCode.MESSAGE_TYPE_UNSUPPORTED);
    }

    /**
     * 是否涉及资金（转账 / 红包）。这类消息发出后既不能撤回也不能转发：
     * 撤回会让收款人失去收款凭证、把红包变成永远领不到的死链；转发则会把「谁能领」的范围
     * 扩大到原会话之外的人。判定收在这个枚举里，消息侧与前端都只认这一个口径。
     */
    public boolean isMoneyBearing() {
        return this == TRANSFER || this == RED_PACKET;
    }

    /**
     * 是否属于附件类消息（图片 / 文件 / 语音），此类消息 content 存 fileId。
     */
    public boolean isAttachment() {
        return this == IMAGE || this == FILE || this == VOICE;
    }
}
