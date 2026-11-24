package com.im.live.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.live.dto.req.LiveMusicCreateRequest;
import com.im.live.dto.vo.WorkVO;

/**
 * 音乐板块（音频信息流）服务：形状同 {@link LiveImageService}，只是本体是一个音频文件。
 *
 * <p>只管用户侧，后台侧走 {@code WorkAdminSpiImpl}，分工理由见 {@link LiveImageService} 注释。
 */
public interface LiveMusicService {

    /** 信息流分页：仅正常状态；参数口径同 {@link LiveImageService#page} */
    Page<WorkVO> page(long current, long size, String category, String keyword, String sort, boolean mine);

    /** 发布音乐，作者取当前登录用户 */
    WorkVO publish(LiveMusicCreateRequest request);

    /** 编辑（仅本人，整表单替换）；非本人 {@code FORBIDDEN}，记录不存在 {@code NOT_FOUND} */
    WorkVO edit(Long id, LiveMusicCreateRequest request);

    /** 删除（仅本人，逻辑删除）；记录不存在幂等返回 */
    void delete(Long id);

    /** 播放计数 +1（原子自增，非强一致） */
    void increasePlayCount(Long id);
}
