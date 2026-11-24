package com.im.friend.spi;

import com.im.common.enums.ProfileVisitType;
import com.im.common.spi.ProfileVisitSpi;
import com.im.friend.service.ProfileVisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link ProfileVisitSpi} 的实现，把 im-user 的「有人看了 TA 的资料卡」落成一行足迹。
 *
 * <p>本类只是一层转接，不直接注 {@code ProfileVisitMapper}：「怎么记一行」的口径（发号、
 * 取当天、{@code view_type} 兜底）与「回看多少天」都集中在 {@link ProfileVisitService}，
 * 写落在 SPI、读落在 Service 的话，30 天窗口就会变成两处各自的说法。
 *
 * <p>不吞异常：一次来访记录是资料卡读取的附属写入，该不该容忍失败由调用方决定
 * （{@code DatingProfileServiceImpl#view} 那边 catch 掉了），在本类里 catch 会让其他调用方
 * 也没机会知道足迹丢了。
 */
@Service
@RequiredArgsConstructor
public class ProfileVisitSpiImpl implements ProfileVisitSpi {

    private final ProfileVisitService profileVisitService;

    @Override
    public void record(Long visitorId, Long visitedId, ProfileVisitType type) {
        profileVisitService.record(visitorId, visitedId, type);
    }
}
