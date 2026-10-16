package com.im.message.handler;

import com.im.common.api.ResultCode;
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
 * 投票消息处理（{@link MsgType#VOTE}）。
 *
 * <p>只校验并定格「投票定义」（标题 + 选项 + 单选/多选/匿名/截止），票数不落 extra，
 * 由 {@code im_message_vote} 表在读取消息列表时实时聚合。规则：
 * <ul>
 *   <li>标题必填、去毒、限长 {@value #MAX_TITLE_LENGTH}；</li>
 *   <li>选项 2 ~ {@value #MAX_OPTIONS} 个，逐个去毒限长 {@value #MAX_OPTION_LENGTH}，丢弃空项后仍需 ≥ 2；</li>
 *   <li>multiple / anonymous 默认 false，closed 发送时恒为 false；</li>
 *   <li>{@code content} 回填为标题，供会话摘要与检索复用。</li>
 * </ul>
 */
@Component
public class VoteContentHandler implements MessageContentHandler {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_OPTIONS = 20;
    private static final int MAX_OPTION_LENGTH = 100;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.VOTE);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();

        String title = TextUtil.sanitize(cmd.getContent() == null ? extra.getVoteTitle() : cmd.getContent());
        title = title == null ? "" : title.trim();
        BusinessException.throwIf(title.isEmpty() || title.length() > MAX_TITLE_LENGTH,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        List<String> rawOptions = extra.getVoteOptions();
        List<String> options = new ArrayList<>();
        if (rawOptions != null) {
            for (String o : rawOptions) {
                if (o == null) {
                    continue;
                }
                String cleaned = TextUtil.sanitize(o).trim();
                if (!cleaned.isEmpty()) {
                    options.add(cleaned.length() <= MAX_OPTION_LENGTH ? cleaned : cleaned.substring(0, MAX_OPTION_LENGTH));
                }
            }
        }
        BusinessException.throwIf(options.size() < 2 || options.size() > MAX_OPTIONS,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        extra.setVoteTitle(title);
        extra.setVoteOptions(options);
        extra.setVoteMultiple(Boolean.TRUE.equals(extra.getVoteMultiple()));
        extra.setVoteAnonymous(Boolean.TRUE.equals(extra.getVoteAnonymous()));
        extra.setVoteClosed(false);
        cmd.setExtra(extra);
        cmd.setContent(title);
    }
}
