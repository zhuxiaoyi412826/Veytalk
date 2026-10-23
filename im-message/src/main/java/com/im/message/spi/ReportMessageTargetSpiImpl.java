package com.im.message.spi;

import com.im.common.constant.ImConstants;
import com.im.common.domain.ReportDisposeCmd;
import com.im.common.domain.ReportDisposeResult;
import com.im.common.domain.ReportTargetDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ConvType;
import com.im.common.enums.MsgType;
import com.im.common.enums.ReportDisposition;
import com.im.common.enums.ReportTargetType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.ReportTargetSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.message.convert.MessageConvert;
import com.im.message.entity.Message;
import com.im.message.mapper.MessageMapper;
import com.im.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「举报一条消息」的证据解析与强制撤回执行。
 *
 * <h2>为什么证据必须在提交那一刻定格</h2>
 *
 * <p>消息是四类可举报对象里<b>最容易被抹掉</b>的一个：发送者自己两分钟内就能撤回，
 * 会话可以被清空，闪照看过即焚。管理员三天后点开工单时如果再去查一次库，
 * 拿到的多半是一条 {@code recalled=1} 的空壳。所以这里把正文摘要、类型、所属会话、
 * 发送时刻一并存进 {@code im_report.evidence}，之后消息怎么变都不影响处置与追溯。
 *
 * <p>摘要复用 {@link MessageConvert#summaryOf}：附件类只出 {@code [图片]} 这种类型占位，
 * 不出内容本体——管理员判定「违规」通常靠文本，图片文件则以 {@code 文件 ID} 的形式留在
 * {@code meta} 里供人工调取，不在工单表里复制一份字节。
 *
 * <h2>责任人反查有两个坑</h2>
 *
 * <ul>
 *   <li><b>系统通知</b>（{@code fromUserId = 0}）没有当事人，{@code reportedUserId} 置空。
 *       不能返回 {@code null} 证据让提交侧报「对象不存在」——消息明明存在，只是没人可警告；
 *       真正的拦截发生在处置那一步，{@code WARN} 的 {@code requiresOwner} 会给出准确提示。</li>
 *   <li><b>机器人消息</b>的 {@code fromUserId} 存的是 {@code im_group_robot} 的 ID，
 *       它<b>不是</b>用户 ID。当成 {@code reportedUserId} 递出去，警告就会推给一个
 *       恰好撞上这个雪花 ID 的真实用户，属于凭空牵连。所以同样置空，机器人名称进 {@code meta}。</li>
 * </ul>
 *
 * <h2>动作集合按这条消息自己能不能被撤回而收窄</h2>
 *
 * <p>{@code supportedDispositions()} 给的是「消息这一类对象」的通用动作，但具体到某一条：
 * 转账 / 红包撤不得（凭证会消失）、系统通知撤不得（业务依据）、已经撤回的没有再撤一次的意义。
 * 这些情况下从返回的证据里摘掉 {@code recall_message}，后台下拉就不会出现一个
 * <b>注定失败</b>的按钮，比让管理员点了才知道强。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportMessageTargetSpiImpl implements ReportTargetSpi {

    /**
     * 摘要本地限长。后台落库前还会再夹一道（{@code im.admin.excerpt-max}），
     * 两道而不是两道互相依赖：契约管不住实现，多一次 substring 换的是工单表不被长聊天记录撑爆。
     */
    private static final int EXCERPT_MAX = 200;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MessageMapper messageMapper;
    private final MessageService messageService;
    private final ConversationSpi conversationSpi;
    private final UserQuerySpi userQuerySpi;

    @Override
    public String targetType() {
        return ReportTargetType.MESSAGE.getCode();
    }

    @Override
    public ReportTargetDTO resolve(Long targetId) {
        if (targetId == null) {
            return null;
        }
        Message message = messageMapper.selectById(targetId);
        if (message == null) {
            // 消息表不做物理删除，查不到就是 ID 写错了；返回 null 让提交侧报 14001
            return null;
        }
        MsgType type = typeOf(message.getMsgType());
        boolean anonymous = isAnonymousSender(message, type);
        Long reportedUserId = anonymous ? null : message.getFromUserId();
        String senderName = resolveSenderName(message, type);

        Map<String, Object> meta = new LinkedHashMap<>(8);
        meta.put("消息类型", type == null ? "未知类型（code=" + message.getMsgType() + "）" : type.getDesc());
        meta.put("发送者", anonymous ? senderName + "（不可警告）" : senderName);
        meta.put("所属会话", conversationLabel(message.getConversationId()));
        meta.put("发送时刻", format(message.getSendTime()));
        meta.put("消息状态", message.isRecalledNow() ? "已撤回" : "正常");
        if (message.getExtra() != null && message.getExtra().getFileId() != null) {
            meta.put("文件 ID", String.valueOf(message.getExtra().getFileId()));
        }
        return ReportTargetDTO.builder()
                .targetType(ReportTargetType.MESSAGE.getCode())
                .targetId(message.getId())
                .reportedUserId(reportedUserId)
                .title("消息 · 来自" + senderName)
                .excerpt(TextUtil.summary(MessageConvert.summaryOf(message, senderName), EXCERPT_MAX))
                .meta(meta)
                .dispositions(dispositionsFor(message, type))
                .build();
    }

    @Override
    public ReportDisposeResult dispose(ReportDisposeCmd cmd) {
        ReportDisposition action = ReportDisposition.of(cmd.getAction());
        if (action != ReportDisposition.RECALL_MESSAGE) {
            return ReportDisposeResult.fail("消息模块不能执行动作「" + cmd.getAction() + "」");
        }
        Message message = messageMapper.selectById(cmd.getTargetId());
        if (message == null) {
            return ReportDisposeResult.fail("该消息已不存在");
        }
        if (message.isRecalledNow()) {
            // 幂等：工单重开或重复处置时不该报「处理失败」，事实已经是撤回状态
            return ReportDisposeResult.ok("这条消息此前已经被撤回，无需重复处理");
        }
        try {
            messageService.adminRecall(message.getId(), cmd.getOperatorId());
        } catch (BusinessException e) {
            // 资金类 / 系统通知的拒绝原因原样回显：管理员需要知道是「不让撤」而不是「撤坏了」
            return ReportDisposeResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[举报处置] 强制撤回消息异常: messageId={}, err={}", message.getId(), e.getMessage());
            return ReportDisposeResult.fail("消息撤回失败：" + e.getMessage());
        }
        return ReportDisposeResult.ok("已强制撤回该消息，会话内各端同步显示");
    }

    /**
     * {@code MsgType.of} 对未知码直接抛异常，而证据解析必须只读、不许把举报提交打断：
     * 将来别处加了新消息类型、这张工单表里留的还是老码时，宁可显示「未知类型」也要把证据落下来。
     */
    private MsgType typeOf(Integer code) {
        try {
            return MsgType.of(code);
        } catch (BusinessException e) {
            return null;
        }
    }

    /**
     * 系统通知与机器人消息都没有可警告的当事人（理由见类注释）。
     */
    private boolean isAnonymousSender(Message message, MsgType type) {
        return type == MsgType.SYSTEM || type == MsgType.ROBOT
                || ImConstants.SYSTEM_USER_ID.equals(message.getFromUserId())
                || message.getFromUserId() == null;
    }

    private String resolveSenderName(Message message, MsgType type) {
        if (type == MsgType.ROBOT && message.getExtra() != null
                && TextUtil.isNotBlank(message.getExtra().getRobotName())) {
            return message.getExtra().getRobotName();
        }
        if (isAnonymousSender(message, type)) {
            return "系统";
        }
        UserBriefDTO sender = userQuerySpi.getById(message.getFromUserId());
        if (sender == null) {
            return "已注销用户";
        }
        return TextUtil.isNotBlank(sender.getNickname()) ? sender.getNickname() : sender.getUsername();
    }

    /**
     * 会话只报类型与 ID，不报会话名称：单聊的展示名依赖查看者视角（好友备注），
     * 举报链路上没有「查看者」，硬调 {@code ConversationSpi#getById} 只会拿到一个
     * 语义不明的名字。ID 足够管理员定位到具体会话。
     */
    private String conversationLabel(Long conversationId) {
        if (conversationId == null) {
            return "—";
        }
        Integer type = conversationSpi.getType(conversationId);
        String kind = type == null ? "会话" : (ConvType.GROUP.getCode() == type ? "群聊" : "单聊");
        return kind + " #" + conversationId;
    }

    private List<String> dispositionsFor(Message message, MsgType type) {
        List<String> codes = new ArrayList<>(supportedDispositions());
        boolean recallable = type != null && !type.isMoneyBearing() && !message.isSystem()
                && !message.isRecalledNow();
        if (!recallable) {
            codes.remove(ReportDisposition.RECALL_MESSAGE.getCode());
        }
        return codes;
    }

    private String format(LocalDateTime time) {
        return time == null ? "—" : TIME_FORMAT.format(time);
    }
}
