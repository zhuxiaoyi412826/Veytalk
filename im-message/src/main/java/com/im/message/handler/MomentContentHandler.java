package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.MomentCardDTO;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.spi.MomentQuerySpi;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 动态分享消息处理（{@link MsgType#MOMENT}）。
 *
 * <p>客户端只上报 {@code extra.momentId}（可选带一句附言放在 {@code content}），
 * 卡片上的作者昵称、正文摘要与附件条数一律由服务端经 {@link MomentQuerySpi} 反查后<b>定格覆写</b>。
 * 这一步不是「省一次前端查询」，而是唯一能防住伪造的地方：这三个字段直接决定气泡显示
 * 「谁说了什么」，采信客户端上报就等于允许任何人造一张挂在别人名下的卡片，
 * 而卡片会被转发、会躺在漫游记录里，比一条普通文本更难追回。
 *
 * <p>同一次反查顺带完成了可见性判定：发送者自己看不到这条动态时直接拒发。
 * 判定口径与动态详情接口一致，所以「能发出去的卡片一定点得开」。
 *
 * <p>★ 不把动态附图的地址抄进 {@code extra}：{@code moment_*} 那两类文件的 {@code fileId}
 * 按约定只能从动态接口下发（见 {@code FileBizType#MOMENT_IMAGE} 的注释），
 * 抄进消息就等于绕开那条边界。气泡只画文字卡片，点开走详情接口按查看者重算一次可见性。
 *
 * <p>im-friend 未装配时（单模块开发形态）直接报「消息类型不支持」而不是降级放行：
 * 没有权威数据源就没有快照可写，写一条 {@code extra} 全空的卡片出去，
 * 接收端只会看到一张点不开也读不懂的空壳。
 */
@Component
@RequiredArgsConstructor
public class MomentContentHandler implements MessageContentHandler {

    private final ImProperties imProperties;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final ObjectProvider<MomentQuerySpi> momentQuerySpiProvider;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.MOMENT);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MomentQuerySpi momentSpi = momentQuerySpiProvider.getIfAvailable();
        BusinessException.throwIf(momentSpi == null, ResultCode.MESSAGE_TYPE_UNSUPPORTED);

        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();
        Long momentId = extra.getMomentId();
        BusinessException.throwIf(momentId == null, ResultCode.MESSAGE_CONTENT_ILLEGAL);

        // 查不到与无权看到都回同一个码：动态 ID 是可以被逐个试的，分开报等于替外部确认存在性
        MomentCardDTO card = momentSpi.loadCard(momentId, cmd.getFromUserId());
        BusinessException.throwIf(card == null, ResultCode.MOMENT_GONE);

        extra.setMomentId(momentId);
        extra.setMomentAuthor(card.getAuthorNickname());
        extra.setMomentSummary(card.getSummary());
        extra.setMomentMediaCount(card.getMediaCount());
        cmd.setExtra(extra);

        // content 存附言（可空）：口径与文本消息一致，超长拒绝而不是静默截断，
        // 敏感词遮蔽而非拒发。气泡按 extra 渲染，附言只作为卡片下面的一行小字
        cmd.setContent(cleanNote(cmd.getContent()));
    }

    /** 附言清洗；空白一律归一成 {@code null}，避免库里存一堆空串与 null 混着的行 */
    private String cleanNote(String raw) {
        String cleaned = TextUtil.sanitize(raw);
        if (TextUtil.isBlank(cleaned)) {
            return null;
        }
        BusinessException.throwIf(cleaned.length() > imProperties.getMessage().getMaxTextLength(),
                ResultCode.MESSAGE_CONTENT_ILLEGAL);
        return sensitiveWordFilter.mask(cleaned);
    }
}
