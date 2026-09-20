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
 * 文本消息处理：XSS 清洗 + 长度校验 + 敏感词遮蔽。
 *
 * <p>超长时直接拒绝而不是静默截断：截断会让用户发出去的话少一半却毫不知情，
 * 明确报错让客户端有机会提示「消息过长」并保留原文。
 *
 * <p>敏感词走替换为 * 而不是拒发：阻断式过滤会让一句无心的口头禅把整条消息吞掉，
 * 用户既不知道原因也无法补救；遮蔽后消息照常送达，语义保留、冒犯性去除。
 * 过滤在 sanitize 之后执行，遮词看到的已是去毒文本，不会被标签形态绕过。
 */
@Component
@RequiredArgsConstructor
public class TextContentHandler implements MessageContentHandler {

    private final ImProperties imProperties;
    private final SensitiveWordFilter sensitiveWordFilter;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.TEXT);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        int maxLength = imProperties.getMessage().getMaxTextLength();
        String cleaned = TextUtil.sanitize(cmd.getContent());
        BusinessException.throwIf(TextUtil.isBlank(cleaned), ResultCode.MESSAGE_CONTENT_ILLEGAL);
        BusinessException.throwIf(cleaned.length() > maxLength, ResultCode.MESSAGE_CONTENT_ILLEGAL);
        // 开关判断收敛在 mask() 里：配置项只是初始值，运行期可被前端「高级设置」热切换
        cleaned = sensitiveWordFilter.mask(cleaned);
        cmd.setContent(cleaned);
        // 文本消息不携带附件元数据，清掉客户端可能误传的 extra，避免脏数据落库；
        // 群聊的 @ 信息由发送主流程在规范化之后统一回填
        cmd.setExtra(null);
    }
}
