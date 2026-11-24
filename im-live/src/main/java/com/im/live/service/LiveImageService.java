package com.im.live.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.live.dto.req.LiveImageCreateRequest;
import com.im.live.dto.vo.WorkVO;

/**
 * 图片板块（图集信息流）服务：与 {@link LiveVideoService} 同一形状，只是发布的是 1~9 张图。
 *
 * <p>只管用户侧（状态恒为正常、要判「是否本人」）。后台侧的 CRUD 走
 * {@code WorkAdminSpiImpl}（{@code AdminWorkSpi} 的实现）：那边要全状态分页、要代建到指定作者、
 * 要落下架事由，两套口径硬拧成一个方法迟早改一边坏一边（{@code VideoAdminSpiImpl} 的注释里
 * 已经说过同样的话）。
 */
public interface LiveImageService {

    /**
     * 信息流分页：仅 {@code status=1}。
     *
     * @param category 二级分类精确过滤，空则不过滤
     * @param keyword  标题模糊搜索，空则不过滤
     * @param sort     {@code hot} 按打开次数倒序，其余按发布时间倒序
     * @param mine     true 只看本人（未登录时返回空页而不是报错：前端「我的」频道本来就允许匿名逛）
     */
    Page<WorkVO> page(long current, long size, String category, String keyword, String sort, boolean mine);

    /** 发布图集，作者取当前登录用户 */
    WorkVO publish(LiveImageCreateRequest request);

    /** 编辑（仅本人，整表单替换）；非本人 {@code FORBIDDEN}，记录不存在 {@code NOT_FOUND} */
    WorkVO edit(Long id, LiveImageCreateRequest request);

    /** 删除（仅本人，逻辑删除）；记录不存在幂等返回 */
    void delete(Long id);

    /** 打开图集计数 +1（原子自增，非强一致） */
    void increasePlayCount(Long id);
}
