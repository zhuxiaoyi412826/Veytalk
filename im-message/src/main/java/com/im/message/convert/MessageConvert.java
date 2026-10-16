package com.im.message.convert;

import com.im.common.constant.ImConstants;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.QuotePreview;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.MessageStatus;
import com.im.common.enums.MsgType;
import com.im.common.util.TextUtil;
import com.im.message.dto.vo.MessageVO;
import com.im.message.entity.Message;

/**
 * 消息实体与传输对象之间的转换。
 *
 * <p>做成静态工具而不是 Spring Bean：转换只依赖入参，没有任何需要注入的协作对象，
 * 声明成 Bean 只会让调用方多一层注入样板代码。
 */
public final class MessageConvert {

    /** 系统通知的展示昵称，发送者 ID 为 0 时没有任何用户资料可查 */
    private static final String SYSTEM_NICKNAME = "系统消息";

    private MessageConvert() {
    }

    /**
     * 转为跨模块传输对象，{@code conversationType} 由调用方从会话模块取得。
     */
    public static MessageDTO toDTO(Message entity, UserBriefDTO sender, Integer conversationType, MessageStatus status) {
        return toDTO(entity, sender, conversationType, status, null);
    }

    /**
     * 转为跨模块传输对象，附带引用预览。
     */
    public static MessageDTO toDTO(Message entity, UserBriefDTO sender, Integer conversationType,
                                   MessageStatus status, QuotePreview quote) {
        boolean recalled = entity.isRecalledNow();
        return MessageDTO.builder()
                .messageId(entity.getId())
                .clientMsgId(entity.getClientMsgId())
                .conversationId(entity.getConversationId())
                .conversationType(conversationType)
                .fromUserId(entity.getFromUserId())
                .fromNickname(displayNickname(sender, entity.getFromUserId(), entity.getExtra()))
                .fromAvatar(displayAvatar(sender, entity.getExtra()))
                .msgType(entity.getMsgType())
                // 撤回后不再下发原文：内容已经对所有人生效失效，继续返回等于撤回功能形同虚设
                .content(recalled ? null : entity.getContent())
                .extra(recalled ? null : entity.getExtra())
                .quote(quote)
                .seq(entity.getSeq())
                .status(status.getCode())
                .recalled(recalled)
                .sendTime(entity.getSendTime())
                .build();
    }

    /**
     * 转为前端视图对象。
     *
     * @param viewerId       当前登录用户，用于判断消息是否为自己发出（决定气泡左右）
     * @param readCount      已读人数
     */
    public static MessageVO toVO(Message entity, UserBriefDTO sender, Long viewerId,
                                 MessageStatus status, int readCount) {
        return toVO(entity, sender, viewerId, status, readCount, null);
    }

    /**
     * 转为前端视图对象，附带引用预览。
     */
    public static MessageVO toVO(Message entity, UserBriefDTO sender, Long viewerId,
                                 MessageStatus status, int readCount, QuotePreview quote) {
        boolean recalled = entity.isRecalledNow();
        boolean self = entity.getFromUserId() != null && entity.getFromUserId().equals(viewerId);
        MsgType type = MsgType.of(entity.getMsgType());
        return MessageVO.builder()
                .messageId(entity.getId())
                .clientMsgId(entity.getClientMsgId())
                .conversationId(entity.getConversationId())
                .fromUserId(entity.getFromUserId())
                .fromNickname(displayNickname(sender, entity.getFromUserId(), entity.getExtra()))
                .fromAvatar(displayAvatar(sender, entity.getExtra()))
                .msgType(entity.getMsgType())
                .msgTypeDesc(type.getDesc())
                .content(recalled ? null : entity.getContent())
                .extra(recalled ? null : flashForViewer(entity.getExtra(), viewerId))
                .quote(quote)
                .seq(entity.getSeq())
                .status(status.getCode())
                .statusDesc(status.getDesc())
                .recalled(recalled)
                .recallTime(entity.getRecallTime())
                .sendTime(entity.getSendTime())
                .self(self)
                .readCount(readCount)
                .build();
    }

    /**
     * 从跨模块传输对象直接构造前端视图，避免发送接口为了组装 VO 再回表查一次消息。
     *
     * <p>{@code MessageDTO} 已经带了发送者昵称头像与消息状态，这里只补上前端才关心的
     * 类型描述、「是否我发的」与已读人数；刚发出的消息必然没有撤回时间与已读回执。
     */
    public static MessageVO toVO(MessageDTO dto, Long viewerId) {
        boolean recalled = Boolean.TRUE.equals(dto.getRecalled());
        boolean self = dto.getFromUserId() != null && dto.getFromUserId().equals(viewerId);
        MsgType type = MsgType.of(dto.getMsgType());
        MessageStatus status = statusOf(dto.getStatus());
        return MessageVO.builder()
                .messageId(dto.getMessageId())
                .clientMsgId(dto.getClientMsgId())
                .conversationId(dto.getConversationId())
                .fromUserId(dto.getFromUserId())
                .fromNickname(dto.getFromNickname())
                .fromAvatar(dto.getFromAvatar())
                .msgType(dto.getMsgType())
                .msgTypeDesc(type.getDesc())
                .content(dto.getContent())
                .extra(flashForViewer(dto.getExtra(), viewerId))
                .quote(dto.getQuote())
                .seq(dto.getSeq())
                .status(status.getCode())
                .statusDesc(status.getDesc())
                .recalled(recalled)
                .sendTime(dto.getSendTime())
                .self(self)
                .readCount(0)
                .build();
    }

    /**
     * 按当前查看者换算闪照的销毁态。
     *
     * <p>闪照是「每人各看一次」而不是全员广播销毁：实体里只存权威的 {@code flashViewedBy} 名单，
     * 下发前复制一份只为当前查看者算出他自己的 {@code flashViewed}，不能直接改实体那份共享对象
     * （否则会把某一个人的已阅状态烤进 extra，坑了其他还没看的人）。
     * 对已阅的人顺带清空 fileUrl：物理文件可能还要等其他人看完才销毁，但这个人不该再有机会拉原图。
     */
    private static MessageExtra flashForViewer(MessageExtra stored, Long viewerId) {
        if (stored == null || !Boolean.TRUE.equals(stored.getFlash())) {
            return stored;
        }
        boolean viewed = viewerId != null
                && stored.getFlashViewedBy() != null
                && stored.getFlashViewedBy().contains(viewerId);
        MessageExtra copy = stored.toBuilder().build();
        copy.setFlashViewed(viewed);
        if (viewed) {
            copy.setFileUrl(null);
        }
        return copy;
    }

    /**
     * 状态码回推枚举，脏数据或新增状态统一当作「已发送」，不让前端拿到 null 而崩掉。
     */
    private static MessageStatus statusOf(Integer code) {
        if (code != null) {
            for (MessageStatus status : MessageStatus.values()) {
                if (status.getCode() == code) {
                    return status;
                }
            }
        }
        return MessageStatus.SENT;
    }

    /**
     * 按消息状态与回执数量推导展示状态。
     *
     * <p>只有「我发出的消息」才需要展示送达 / 已读：别人的消息在我这边的状态就是「已收到」，
     * 显示成「已送达」反而会让用户误以为是自己发的。
     */
    public static MessageStatus resolveStatus(Message entity, boolean self, int deliveredCount, int readCount) {
        if (entity.isRecalledNow()) {
            return MessageStatus.RECALLED;
        }
        if (!self) {
            return MessageStatus.SENT;
        }
        if (readCount > 0) {
            return MessageStatus.READ;
        }
        if (deliveredCount > 0) {
            return MessageStatus.DELIVERED;
        }
        return MessageStatus.SENT;
    }

    /**
     * 会话列表展示用的摘要文案：附件类消息不展示内容本体，只给一个类型占位。
     *
     * @param senderNickname 发送者昵称，撤回文案需要用到，可为空
     */
    public static String summaryOf(Message entity, String senderNickname) {
        if (entity.isRecalledNow()) {
            return recallSummary(senderNickname);
        }
        MsgType type = MsgType.of(entity.getMsgType());
        return switch (type) {
            // 闪照在会话列表/推送摘要里要和普通图片区分开，否则接收方不知道点开会阅后即焚
            case IMAGE -> entity.getExtra() != null && Boolean.TRUE.equals(entity.getExtra().getFlash())
                    ? "[闪照]" : "[图片]";
            case VOICE -> "[语音]";
            case FILE -> {
                String fileName = entity.getExtra() == null ? null : entity.getExtra().getFileName();
                yield TextUtil.isBlank(fileName) ? "[文件]" : "[文件] " + fileName;
            }
            case TEXT, SYSTEM, ROBOT -> TextUtil.isBlank(entity.getContent()) ? "[" + type.getDesc() + "]" : entity.getContent();
            // 通话记录：content 是录制文件 ID（可空），不能直出，给类型占位并附时长
            case CALL -> callSummary(entity);
            // 名片：content 存的是昵称快照，摘要统一加 [名片] 前缀区分普通文本
            case CARD -> "[名片] " + (entity.getExtra() == null || TextUtil.isBlank(entity.getExtra().getCardNickname())
                    ? "联系人" : entity.getExtra().getCardNickname());
            // 位置：content 存的是文字地址，摘要加 [位置] 前缀
            case LOCATION -> "[位置] " + (entity.getExtra() == null || TextUtil.isBlank(entity.getExtra().getLocationName())
                    ? "地图位置" : entity.getExtra().getLocationName());
            // 合并转发：content 存的是标题，摘要加 [聊天记录] 前缀
            case MERGED_FORWARD -> "[聊天记录] " + (entity.getExtra() == null || TextUtil.isBlank(entity.getExtra().getMergedTitle())
                    ? "会话记录" : entity.getExtra().getMergedTitle());
            // 投票：content 存的是标题，摘要加 [投票] 前缀
            case VOTE -> "[投票] " + (entity.getExtra() == null || TextUtil.isBlank(entity.getExtra().getVoteTitle())
                    ? entity.getContent() : entity.getExtra().getVoteTitle());
        };
    }

    /**
     * 通话记录摘要：优先按媒体类型给占位（语音/视频通话），有录制文件时附时长。
     */
    private static String callSummary(Message entity) {
        MessageExtra extra = entity.getExtra();
        String base = (extra != null && "video".equals(extra.getCallMediaType())) ? "[视频通话]" : "[语音通话]";
        if (extra == null || extra.getDuration() == null || extra.getDuration() <= 0) {
            return base;
        }
        return base + " " + formatDuration(extra.getDuration());
    }

    /** 秒转 mm:ss，超过一小时补时位 */
    private static String formatDuration(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return h > 0
                ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%02d:%02d", m, s);
    }

    /**
     * 撤回占位文案，会话摘要与前端气泡共用同一句话，避免出现两种说法。
     */
    public static String recallSummary(String senderNickname) {
        String who = TextUtil.isBlank(senderNickname) ? "对方" : senderNickname;
        return who + "撤回了一条消息";
    }

    /**
     * 系统通知的发送者 ID 为 0，查不到用户资料，需要给一个固定昵称兖底。
     */
    private static String nicknameOf(UserBriefDTO sender, Long fromUserId) {
        if (ImConstants.SYSTEM_USER_ID.equals(fromUserId)) {
            return SYSTEM_NICKNAME;
        }
        if (sender == null) {
            return fromUserId == null ? null : "用户" + fromUserId;
        }
        if (TextUtil.isNotBlank(sender.getNickname())) {
            return sender.getNickname();
        }
        return TextUtil.isNotBlank(sender.getUsername()) ? sender.getUsername() : "用户" + sender.getUserId();
    }

    /**
     * 机器人消息的展示昵称：优先用 extra 里的机器人名称快照。
     *
     * <p>机器人没有真实用户账号，{@code fromUserId} 查不到资料，{@code sender} 恒为 null；
     * 名称与头像在发送时已随消息定格进 extra，这里直接取用，接收端无需（也无处）再查库。
     */
    private static String displayNickname(UserBriefDTO sender, Long fromUserId, MessageExtra extra) {
        if (extra != null && TextUtil.isNotBlank(extra.getRobotName())) {
            return extra.getRobotName();
        }
        return nicknameOf(sender, fromUserId);
    }

    /**
     * 机器人消息的展示头像：机器人取 extra 快照，其余取发送者资料头像。
     */
    private static String displayAvatar(UserBriefDTO sender, MessageExtra extra) {
        if (extra != null && TextUtil.isNotBlank(extra.getRobotName())) {
            return extra.getRobotAvatar();
        }
        return sender == null ? null : sender.getAvatar();
    }

    /**
     * 构建引用消息预览。
     *
     * <p>已撤回的原消息只保留标记，不下发内容摘要——撤回的语义是「对所有人失效」，
     * 引用块里继续展示原文等于绕过了撤回。
     */
    public static QuotePreview toQuotePreview(Message entity, UserBriefDTO sender) {
        if (entity == null) {
            return null;
        }
        boolean recalled = entity.isRecalledNow();
        return QuotePreview.builder()
                .messageId(entity.getId())
                .fromUserId(entity.getFromUserId())
                .fromNickname(nicknameOf(sender, entity.getFromUserId()))
                .msgType(entity.getMsgType())
                .content(recalled ? null : summaryOf(entity, null))
                .recalled(recalled)
                .build();
    }
}
