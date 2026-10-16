package com.im.live.spi;

import com.im.common.spi.LiveRoomSpi;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.LiveRoomMapper;
import com.im.live.service.LiveDanmakuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link LiveRoomSpi} 实现：把房间直播态与「本场观众」判定暴露给 im-websocket 的连麦信令。
 *
 * <p>刻意只做三件极窄的读操作，不把 im-live 的内部结构泄漏出去；信令侧要「房间是否直播中、主播是谁、
 * 被邀者是否在房」都靠这三问收敛，其余连麦态（申请、在麦嘉宾、协商）全部留在 im-websocket 自己维护。
 */
@Service
@RequiredArgsConstructor
public class LiveRoomSpiImpl implements LiveRoomSpi {

    private final LiveRoomMapper roomMapper;
    private final LiveDanmakuService danmakuService;

    @Override
    public boolean isLiving(Long roomId) {
        if (roomId == null) {
            return false;
        }
        LiveRoom room = roomMapper.selectById(roomId);
        return room != null && room.isLiving();
    }

    @Override
    public Long anchorIdOf(Long roomId) {
        if (roomId == null) {
            return null;
        }
        LiveRoom room = roomMapper.selectById(roomId);
        return room == null ? null : room.getAnchorId();
    }

    @Override
    public boolean isActiveViewer(Long roomId, Long userId) {
        return roomId != null && danmakuService.hasUser(roomId, userId);
    }

    @Override
    public void publishLinkmic(Long roomId, boolean active) {
        if (roomId == null) {
            return;
        }
        danmakuService.broadcastLinkmic(roomId, active);
    }
}
