package com.im.live.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.live.dto.req.LiveStartRequest;
import com.im.live.dto.vo.LiveRoomVO;
import com.im.live.entity.LiveRoom;

import java.util.Map;

/**
 * 直播房间服务：开播、关播、心跳、列表与签名播放地址。
 *
 * <p>本服务是<b>控制面</b>——它一个媒体字节都不碰。画面从推流端 ffmpeg 直接 PUT 到
 * Nginx，观众从 Nginx 拉流；这里只决定「谁能开播、房间活着没有、播放地址怎么签名」。
 */
public interface LiveRoomService {

    /**
     * 开播：新建一场直播并下发推流地址。
     *
     * @return roomId / streamKey / pushUrl / playUrl / heartbeatSeconds / danmakuWs，
     *         其中 streamKey 只在此处返回一次
     */
    Map<String, Object> start(LiveStartRequest request);

    /**
     * 关播。
     *
     * @param reason stop 主播主动 / timeout 心跳超时 / ban 管理封禁
     */
    void stop(Long roomId, String reason);

    /**
     * 推流端心跳。
     *
     * @return {@code true} 房间仍在直播；{@code false} 房间已不存在或已结束——
     *         推流端收到 false 必须停止 ffmpeg 进程，否则切片会一直往一个没人看的目录里写
     */
    boolean heartbeat(Long roomId);

    /** 房间详情（含签名播放地址，仅直播中） */
    LiveRoomVO detail(Long roomId);

    /**
     * 房间分页。
     *
     * @param status 为空表示全部；直播中的房间恒排在最前
     */
    Page<LiveRoomVO> page(long current, long size, Integer status);

    /** 我最近的一场直播（直播中优先），用于「我的直播」面板恢复现场；一场都没开过返回 {@code null} */
    LiveRoomVO mine();

    /** 巡检：把心跳超时的房间关播，并冲刷点赞合并计数 */
    void expireTimeouts();

    /** 取一个直播中的房间，不存在或已结束返回 {@code null}（弹幕握手用） */
    LiveRoom findLiving(Long roomId);
}
