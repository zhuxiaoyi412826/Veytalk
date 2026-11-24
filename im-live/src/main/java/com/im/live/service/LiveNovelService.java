package com.im.live.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.live.dto.req.LiveNovelCreateRequest;
import com.im.live.dto.vo.WorkVO;

/**
 * 小说板块（Markdown 长篇信息流）服务：比图片/音乐多一个 {@link #detail(Long)}。
 *
 * <p>多的那一个不是设计上的偏爱，而是 {@code content} 是 LONGTEXT：列表必须把它排除在外
 * （一屏 15 篇长文就能把接口拖成内存黑洞），所以前端点开某一篇时要单独取一次正文。
 * {@link #page(long, long, String, String, String, boolean)} 返回的 VO 恒不含 {@code content}。
 *
 * <p>只管用户侧，后台侧走 {@code WorkAdminSpiImpl}，分工理由见 {@link LiveImageService} 注释。
 */
public interface LiveNovelService {

    /** 信息流分页：仅正常状态，<b>不取正文列</b>；参数口径同 {@link LiveImageService#page} */
    Page<WorkVO> page(long current, long size, String category, String keyword, String sort, boolean mine);

    /**
     * 阅读详情：含正文，并顺手把阅读数 +1。
     *
     * <p>已下架的行不给读（回 {@code NOT_FOUND}）：下架就是「不再对外提供」，只进后台列表——
     * 与用户端分页恒定 {@code status=1} 同一口径（前台任何入口都看不到它，详情也不该是个后门）。
     */
    WorkVO detail(Long id);

    /** 发布小说，作者取当前登录用户；字数与字节数服务端现算 */
    WorkVO publish(LiveNovelCreateRequest request);

    /** 编辑（仅本人，整表单替换）；非本人 {@code FORBIDDEN}，记录不存在 {@code NOT_FOUND} */
    WorkVO edit(Long id, LiveNovelCreateRequest request);

    /** 删除（仅本人，逻辑删除）；记录不存在幂等返回 */
    void delete(Long id);

    /** 阅读计数 +1（原子自增，非强一致）；详情页走 {@link #detail} 时已自增，本方法给「只看卡片不展开」的场景 */
    void increasePlayCount(Long id);
}
