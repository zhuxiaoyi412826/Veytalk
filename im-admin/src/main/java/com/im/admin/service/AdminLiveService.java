package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminLiveDTO;
import com.im.common.domain.AdminLiveQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminLiveSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台直播管理：全部场次翻页、单场档案、强制关播。
 *
 * <p>同 {@code AdminUserService} 的薄封装模式：本类不直接读 {@code im_live_room}，
 * 一切经 {@code AdminLiveSpi} 交给 im-live；强制关播在 im-live 内部再收敛到
 * {@code LiveRoomService#stop(roomId, "ban")} 这一个落点（与举报处置共用关房路径）。
 *
 * <p>「直播中恒排最前」等口径由 SPI 实现侧写死，这里不接受任何排序参数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLiveService {

    private final ObjectProvider<AdminLiveSpi> liveSpiProvider;

    public PageResult<AdminLiveDTO> page(AdminLiveQueryDTO query) {
        return requireSpi().pageRooms(query);
    }

    public AdminLiveDTO detail(Long roomId) {
        AdminLiveDTO room = requireSpi().detail(roomId);
        if (room == null) {
            throw new BusinessException(ResultCode.LIVE_ROOM_NOT_FOUND);
        }
        return room;
    }

    /**
     * 强制关播。SPI 侧对「已不在直播中」幂等跳过，所以关不关得动都以
     * 处置后的最新档案为返回——前端拿回来直接刷新那一行，不用自己猜。
     */
    public AdminLiveDTO forceStop(Long roomId, Long operatorId) {
        AdminLiveSpi spi = requireSpi();
        spi.forceStop(roomId, operatorId);
        log.info("[后台直播管理] 强制关播指令完成: roomId={}, operator={}", roomId, operatorId);
        AdminLiveDTO room = spi.detail(roomId);
        return room;
    }

    private AdminLiveSpi requireSpi() {
        AdminLiveSpi spi = liveSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "直播模块未装配，后台无法管理直播");
        }
        return spi;
    }
}
