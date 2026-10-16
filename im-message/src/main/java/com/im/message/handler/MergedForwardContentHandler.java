package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.domain.MergedMessageItem;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 合并转发（聊天记录）消息处理（{@link MsgType#MERGED_FORWARD}）。
 *
 * <p>快照列表由前端在发送时定格（合并的是发送者此刻可见的视图），服务端做结构收敛而非重新回表取原文：
 * <ul>
 *   <li>标题必填、去毒、限长；</li>
 *   <li>快照条数上限 {@value #MAX_ITEMS} 条，防止把整个会话打包成一条巨消息撑爆 extra JSON 列；</li>
 *   <li>每条快照的昵称 / 摘要去毒并限长，msgType 原样保留供查看器区分占位；</li>
 *   <li>正文 {@code content} 回填为标题，供会话摘要与检索复用。</li>
 * </ul>
 */
@Component
public class MergedForwardContentHandler implements MessageContentHandler {

    private static final int MAX_ITEMS = 100;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MAX_NICKNAME_LENGTH = 64;
    private static final int MAX_SUMMARY_LENGTH = 500;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.MERGED_FORWARD);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();
        String title = TextUtil.sanitize(cmd.getContent() == null ? extra.getMergedTitle() : cmd.getContent());
        title = title == null ? "" : title.trim();
        BusinessException.throwIf(title.isEmpty() || title.length() > MAX_TITLE_LENGTH,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        List<MergedMessageItem> source = extra.getMergedMessages();
        BusinessException.throwIf(source == null || source.isEmpty(), ResultCode.MESSAGE_CONTENT_ILLEGAL);
        BusinessException.throwIf(source.size() > MAX_ITEMS, ResultCode.MESSAGE_CONTENT_ILLEGAL);

        List<MergedMessageItem> cleaned = new ArrayList<>(source.size());
        for (MergedMessageItem item : source) {
            if (item == null) {
                continue;
            }
            String nickname = clamp(sanitize(item.getNickname()), MAX_NICKNAME_LENGTH);
            String summary = clamp(sanitize(item.getSummary()), MAX_SUMMARY_LENGTH);
            cleaned.add(MergedMessageItem.builder()
                    .nickname(nickname)
                    .msgType(item.getMsgType())
                    .summary(summary)
                    .time(item.getTime())
                    .build());
        }
        BusinessException.throwIf(cleaned.isEmpty(), ResultCode.MESSAGE_CONTENT_ILLEGAL);

        extra.setMergedTitle(title);
        extra.setMergedMessages(cleaned);
        cmd.setExtra(extra);
        cmd.setContent(title);
    }

    private String sanitize(String s) {
        return s == null ? "" : TextUtil.sanitize(s).trim();
    }

    private String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
