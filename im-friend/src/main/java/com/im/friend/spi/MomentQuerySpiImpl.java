package com.im.friend.spi;

import com.im.common.domain.MomentCardDTO;
import com.im.common.spi.MomentQuerySpi;
import com.im.friend.service.MomentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link MomentQuerySpi} 的实现，纯转发给 {@link MomentService}。
 *
 * <p>刻意不在这个类里重写一遍可见性判定：判据一旦有两份，漏的那一份永远是「多给出去」的那一边，
 * 而这条契约的消费者是消息模块——它拿到的卡片会被转发、会躺在漫游记录里，
 * 比一次动态详情请求活得久得多，正是最不能有第二套口径的地方。
 *
 * <p>{@code null} 一路透传：查不到与无权看到都返回 {@code null}，由
 * {@code MomentContentHandler} 回一个「这条动态不在了」，不在这里替它挑错误码。
 */
@Component
@RequiredArgsConstructor
public class MomentQuerySpiImpl implements MomentQuerySpi {

    private final MomentService momentService;

    @Override
    public MomentCardDTO loadCard(Long momentId, Long viewerId) {
        return momentService.cardOf(momentId, viewerId);
    }
}
