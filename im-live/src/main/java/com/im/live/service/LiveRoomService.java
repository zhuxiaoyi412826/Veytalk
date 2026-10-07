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
     * 撤销一场从未推流成功的直播：建房成功但 ffmpeg 起不来时前端调这里<b>直接删房</b>，
     * 而不是走 stop 留一个必然空场的 ENDED 房（聊天模式下还会在大厅挂 1 小时）。
     *
     * <p>安全护栏：仅当房间仍 living 且<b>从未收到过心跳</b>（心跳键不存在 = 推流端根本没跑起来）
     * 才允许删除；已推过流的房间降级为普通 stop，防止主播拿 abort 当「删直播记录」用。
     * 房间不存在时幂等返回。
     */
    void abort(Long roomId);

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
     * @param status 为空表示全部；直播中的房间恒排在最前。
     *               另：已结束且关播超过 {@code endedRoomVisibleMinutes}（默认 60 分钟）的房间不在列表中返回
     */
    Page<LiveRoomVO> page(long current, long size, Integer status);

    /** 我最近的一场直播（直播中优先），用于「我的直播」面板恢复现场；一场都没开过返回 {@code null} */
    LiveRoomVO mine();

    /** 巡检：把心跳超时的房间关播、清理聊天模式到期的聊天室，并冲刷点赞合并计数 */
    void expireTimeouts();

    /**
     * 取一个可进房（弹幕握手用）的房间：直播中，或已结束但聊天模式未超时
     * （见 {@code im.live.ended-chat-minutes}）。已封禁 / 聊天超时 / 不存在返回 {@code null}。
     */
    LiveRoom findJoinable(Long roomId);

    /** 结束原因码（stop/timeout/ban）→ 观众可读文案；关播广播与聊天模式进房提示都走这个映射 */
    String endReasonText(String reason);
}
