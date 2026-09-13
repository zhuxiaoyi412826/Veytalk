package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 系统通知处理。
 *
 * <p>系统通知只能由服务端内部生成（好友申请通过、入群退群、群解散等），
 * 「不允许客户端伪造系统通知」的准入校验放在 {@code MessageServiceImpl}，
 * 这里只负责文案清洗：通知内容会直接出现在会话列表摘要里，同样需要防注入。
 */
@Component
public class SystemContentHandler implements MessageContentHandler {

    /** 系统通知文案上限，与会话摘要列长度保持同一量级 */
    private static final int NOTICE_MAX_LENGTH = 200;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.SYSTEM);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        String cleaned = TextUtil.sanitize(cmd.getContent());
        BusinessException.throwIf(TextUtil.isBlank(cleaned), ResultCode.MESSAGE_CONTENT_ILLEGAL);
        cmd.setContent(TextUtil.summary(cleaned, NOTICE_MAX_LENGTH));
    }
}
