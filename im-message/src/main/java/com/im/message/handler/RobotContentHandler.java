package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 机器人消息处理：文本清洗与敏感词遮蔽，但保留 {@code extra}。
 *
 * <p>与 {@link TextContentHandler} 的关键区别在于这里绝不清空 extra：机器人身份（名称、头像快照）
 * 就携带在 extra 里，而机器人对应的 {@code fromUserId} 并非真实用户账号，一旦抹掉 extra，
 * 前端拿到的将是一个查无此人的发送者。文本处理器之所以敢清 extra，是因为普通文本没有需要
 * 随消息落库的元数据；机器人消息有，故单列一个处理器。
 *
 * <p>本类型只能由 {@code internal=true} 的 Webhook 路径产生（发送主流程已拦截客户端伪造），
 * 因此这里只需保证正文安全（XSS 清洗 + 敏感词遮蔽 + 长度），无需再校验附件归属。
 */
@Component
@RequiredArgsConstructor
public class RobotContentHandler implements MessageContentHandler {

    private final ImProperties imProperties;
    private final SensitiveWordFilter sensitiveWordFilter;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.ROBOT);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        int maxLength = imProperties.getMessage().getMaxTextLength();
        String cleaned = TextUtil.sanitize(cmd.getContent());
        BusinessException.throwIf(TextUtil.isBlank(cleaned), ResultCode.MESSAGE_CONTENT_ILLEGAL);
        BusinessException.throwIf(cleaned.length() > maxLength, ResultCode.MESSAGE_CONTENT_ILLEGAL);
        cmd.setContent(sensitiveWordFilter.mask(cleaned));
    }
}
