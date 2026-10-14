package com.im.live.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.live.dto.req.LiveVideoCreateRequest;
import com.im.live.dto.vo.LiveVideoVO;

/**
 * 直播页「视频」板块服务：短视频信息流的分页、发布、删除与播放计数。
 *
 * <p>与 {@code LiveRoomService} 同属控制面，但更薄——不涉及推流、心跳与签名，只是
 * 「一条文件记录 + 一份展示元数据」的增删查。视频与封面本体的上传走通用 {@code im-file}，
 * 本服务只在 {@code im_live_video} 里落一条信息流记录。
 */
public interface LiveVideoService {

    /**
     * 信息流分页：仅 {@code status=1} 的正常视频，按发布时间倒序；批量补全上传者昵称/头像。
     */
    Page<LiveVideoVO> page(long current, long size);

    /**
     * 发布：据前端上传返回的 fileId/coverFileId 建一行信息流记录，服务端拼受控访问地址。
     */
    LiveVideoVO create(LiveVideoCreateRequest request);

    /**
     * 删除（逻辑删除）：仅上传者本人可删，否则 {@code FORBIDDEN}；记录不存在幂等返回。
     */
    void delete(Long id);

    /**
     * 播放计数 +1。记录不存在时静默返回——播放是展示侧的锦上添花，不该因并发删除而报错。
     */
    void increasePlayCount(Long id);
}
