package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;

/**
 * 接龙消息处理（{@link MsgType#CHAIN}）。
 *
 * <p>发送阶段只定格「接龙定义」：主题必填、去毒、限长，条目列表一律清空、{@code chainClosed=false}。
 * 条目由后续的 {@code MessageService#chain} 追加接口在服务端 read-modify-write 写入——
 * 这里<b>完全忽略客户端传来的 chainItems</b>：昵称与服务端序号必须由服务端权威生成，
 * 客户端既伪造不了别人的身份，也无法靠一次发送就往接龙里塞任意多条。
 *
 * <p>{@code content} 回填为主题，供会话摘要与关键词检索复用（与 {@code VoteContentHandler} 同构）。
 */
@Component
public class ChainContentHandler implements MessageContentHandler {

    private static final int MAX_TITLE_LENGTH = 200;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.CHAIN);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();

        String title = TextUtil.sanitize(cmd.getContent() == null ? extra.getChainTitle() : cmd.getContent());
        title = title == null ? "" : title.trim();
        BusinessException.throwIf(title.isEmpty() || title.length() > MAX_TITLE_LENGTH,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        extra.setChainTitle(title);
        // 条目与截止态不接受客户端上报：发送时恒为空、未截止，之后只能走追加接口改
        extra.setChainItems(new ArrayList<>());
        extra.setChainClosed(false);
        cmd.setExtra(extra);
        cmd.setContent(title);
    }
}
