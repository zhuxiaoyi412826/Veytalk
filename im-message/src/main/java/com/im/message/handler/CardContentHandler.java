package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 名片消息处理（{@link MsgType#CARD}）。
 *
 * <p>客户端只上报被分享用户的 ID（{@code extra.cardUserId}，兼容把 ID 放在 {@code content} 的写法），
 * 昵称与头像一律由服务端按该 ID 查库后<b>定格快照</b>进 {@code extra}：
 * 一来接收端渲染名片不需要再回表查用户，二来防止有人拿别人的用户 ID 伪造昵称 / 头像外发。
 *
 * <p>校验：目标用户必须存在且不是系统账号（ID 0）。名片不携带附件，{@code content} 回填为昵称快照，
 * 便于会话摘要与内容检索复用同一字段。
 */
@Component
@RequiredArgsConstructor
public class CardContentHandler implements MessageContentHandler {

    private final UserQuerySpi userQuerySpi;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.CARD);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();
        Long targetId = extra.getCardUserId() != null ? extra.getCardUserId() : parseId(cmd.getContent());
        BusinessException.throwIf(targetId == null || ImConstants.SYSTEM_USER_ID.equals(targetId),
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        UserBriefDTO user = userQuerySpi.getById(targetId);
        BusinessException.throwIf(user == null, ResultCode.USER_NOT_FOUND);

        String nickname = TextUtil.isNotBlank(user.getNickname()) ? user.getNickname()
                : TextUtil.isNotBlank(user.getUsername()) ? user.getUsername() : "用户" + targetId;
        // 服务端权威快照：不信任客户端上报的昵称 / 头像，全部按 ID 重查覆盖
        extra.setCardUserId(targetId);
        extra.setCardNickname(nickname);
        extra.setCardAvatar(user.getAvatar());
        cmd.setExtra(extra);
        // content 存昵称快照：摘要与检索都读它，前端 CARD 分支按 extra 渲染、不直出 content
        cmd.setContent(nickname);
    }

    /** content 兜底解析：客户端没放 extra.cardUserId 时把 content 当数字 ID */
    private Long parseId(String content) {
        if (TextUtil.isBlank(content)) {
            return null;
        }
        try {
            return Long.parseLong(content.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
