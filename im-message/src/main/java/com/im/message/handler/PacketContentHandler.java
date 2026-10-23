package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 红包消息处理（{@link MsgType#RED_PACKET}）。
 *
 * <p>与转账一样只做定格与清洗：总额、份数与祝福语由 im-user 的红包服务在<b>钱已经扣走之后</b>传来，
 * 本模块不参与任何资金判断，也不持有领取状态。
 *
 * <p>领取名单为什么不落 {@code extra}：那是一份会一直变的数据（谁点了、抢到多少），
 * 消息表存它就等于把一条不可变的历史记录改成账本，而且每次领取都要重播整条消息。
 * 前端点开气泡时向 {@code /api/wallet/packet/{id}} 现取，消息里只留 {@code packetId} 这个寻址用的钩子。
 *
 * <p>规则：{@code packetId} 与总额必填（正数），份数 1 ~ {@value #MAX_COUNT}，
 * 祝福语去毒限长 {@value #MAX_GREETING_LENGTH}、空白兜底为「恭喜发财，大吉大利」；
 * {@code content} 存祝福语，供会话内关键词检索复用。
 */
@Component
@RequiredArgsConstructor
public class PacketContentHandler implements MessageContentHandler {

    private static final int MAX_GREETING_LENGTH = 30;
    private static final int MAX_COUNT = 200;
    private static final String DEFAULT_GREETING = "恭喜发财，大吉大利";

    /** 祝福语与转账附言一样是用户自由文本，走与文本消息同一口径的敏感词遮蔽 */
    private final SensitiveWordFilter sensitiveWordFilter;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.RED_PACKET);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra();
        BusinessException.throwIf(extra == null, ResultCode.MESSAGE_CONTENT_ILLEGAL);

        Integer count = extra.getPacketCount();
        Long amount = extra.getPacketAmount();
        // 总额必须撑得起份数：每份至少 1 积分，否则红包池里会出现领到 0 的空名额
        BusinessException.throwIf(extra.getPacketId() == null
                        || amount == null || amount <= 0
                        || count == null || count < 1 || count > MAX_COUNT
                        || amount < count,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        String greeting = null;
        if (extra.getPacketGreeting() != null) {
            // 先 sanitize 再 mask，与 TextContentHandler 同序：先清标签形态再比词，否则可用标签写法绕过词表
            String cleaned = sensitiveWordFilter.mask(TextUtil.sanitize(extra.getPacketGreeting()).trim());
            if (!cleaned.isEmpty()) {
                greeting = cleaned.length() <= MAX_GREETING_LENGTH ? cleaned : cleaned.substring(0, MAX_GREETING_LENGTH);
            }
        }
        extra.setPacketGreeting(greeting == null ? DEFAULT_GREETING : greeting);
        cmd.setExtra(extra);
        cmd.setContent(extra.getPacketGreeting());
    }
}
