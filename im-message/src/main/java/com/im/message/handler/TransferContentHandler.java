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
 * 转账消息处理（{@link MsgType#TRANSFER}）。
 *
 * <p>这条链路上「钱」的部分完全发生在 im-user 的转账服务里：余额已经真扣、双方流水已经落账，
 * 才带着定好的金额与收款人回调消息模块。所以本处理器只做<b>防御性的定格与清洗</b>，不做任何资金判断：
 * <ul>
 *   <li>金额必须为正数——零与负额的转账气泡没有任何意义，而它只可能来自构造请求；</li>
 *   <li>收款人 ID 必填，昵称快照去毒限长 {@value #MAX_NICKNAME_LENGTH}；</li>
 *   <li>附言去毒限长 {@value #MAX_MEMO_LENGTH}，空白归一为 {@code null}（前端按「无附言」渲染）；</li>
 *   <li>{@code content} 存附言（无则存「转账」），供会话内关键词检索复用同一份正文。</li>
 * </ul>
 *
 * <p>伪造防护不靠这里：{@code MessageServiceImpl.send} 已要求本类型必须 {@code internal=true}，
 * 客户端直接发到 {@code /api/message/send} 的 12 型消息在进门时就被拒。
 */
@Component
@RequiredArgsConstructor
public class TransferContentHandler implements MessageContentHandler {

    private static final int MAX_MEMO_LENGTH = 60;
    private static final int MAX_NICKNAME_LENGTH = 64;

    /** 附言是用户自由文本，与文本消息同一口径过敏感词；昵称快照只是展示字段，清洗不遮词 */
    private final SensitiveWordFilter sensitiveWordFilter;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.TRANSFER);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra();
        BusinessException.throwIf(extra == null, ResultCode.MESSAGE_CONTENT_ILLEGAL);

        Long amount = extra.getTransferAmount();
        BusinessException.throwIf(amount == null || amount <= 0 || extra.getTransferToUserId() == null,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        String memo = clean(extra.getTransferMemo(), MAX_MEMO_LENGTH, true);
        extra.setTransferMemo(memo);
        extra.setTransferToNickname(clean(extra.getTransferToNickname(), MAX_NICKNAME_LENGTH, false));
        cmd.setExtra(extra);
        cmd.setContent(memo == null ? MsgType.TRANSFER.getDesc() : memo);
    }

    /**
     * 去毒 + 可选敏感词遮蔽 + 去空白 + 限长，空串归一为 {@code null}：前端只要判一个 null 就能决定渲染与否。
     *
     * <p>顺序固定为「先 sanitize 再 mask」，与 {@code TextContentHandler} 一致：
     * 先清标签形态再比词，否则可以用 {@code 修&#x0031;} 这类写法绕过词表。
     */
    private String clean(String raw, int maxLength, boolean maskSensitive) {
        if (raw == null) {
            return null;
        }
        String cleaned = TextUtil.sanitize(raw).trim();
        if (maskSensitive) {
            cleaned = sensitiveWordFilter.mask(cleaned);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
