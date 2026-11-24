package com.im.common.spi;

import com.im.common.domain.AdminWorkCategoryDTO;

import java.util.List;

/**
 * 后台板块分类字典契约，由 im-live 实现（{@code WorkCategoryAdminSpiImpl}）。
 *
 * <p>{@code im_live_video_category} 是一张表吃四个板块（靠 {@code type} 列区分），所以分类的
 * 增删改本质上是一套逻辑加一个板块参数。写成每板块一份，就会有三份只差字符串的同构代码。
 *
 * <p><b>{@code video} 也从这里走</b>：视频板块既有的四个后台端点
 * （{@code /api/admin/video/category*}）URL、返回 DTO 与前端一律不动，只是实现内部改为
 * 委托本契约并固定传 {@code video}。留在这儿的理由：让「改名/调序/启停」的校验只有一份实现，
 * 否则四板块里改两板块，漏改的那两板块会静默地行为不一致（同名校验范围就是第一个会漏的点）。
 *
 * <p>与作品契约 {@link AdminWorkSpi} 分开：分类是字典、作品是内容，两者的权限点虽然同一屏，
 * 但生命周期与校验口径不同（分类的同名唯一性只在「未删除 + 同板块」范围内成立）。
 */
public interface AdminWorkCategorySpi {

    /**
     * 某板块的全部分类（含停用），按 sort 升序。
     *
     * @param type video / image / music / novel，未知值由实现方拒绝
     */
    List<AdminWorkCategoryDTO> listCategories(String type);

    /**
     * 新增分类。
     *
     * @param type  归属板块，服务端写死来源（用户端接口不接前端传值）
     * @param sort  排序，{@code null} 时排到最后；同名（<b>同板块</b>未删除行内）由实现方拒绝
     * @param operatorId 操作管理员，进审计日志
     */
    AdminWorkCategoryDTO createCategory(String type, String name, Integer sort, Long operatorId);

    /**
     * 修改分类：改名 / 调序 / 启停。参数传 {@code null} 表示该项不改。
     *
     * <p>不允许改 {@code type}：把一行分类挪到另一个板块等于把它名下所有作品的分类文本变成孤儿。
     *
     * <p><b>但 {@code type} 入参仍然必传</b>：它是调用方声明的「我在哪一个屏上操作」，实现方
     * 必须比对行自身的 type 并拒绝不一致——否则只有 {@code system:image:manage} 的管理员
     * 就能拿着一个小说分类的 ID 改到小说的字典里，而「一屏一个权限点」约定依赖它真成立。
     *
     * @param status 目标状态（1 启用 0 停用），{@code null} 不改
     */
    AdminWorkCategoryDTO updateCategory(String type, Long id, String name, Integer sort, Integer status, Long operatorId);

    /**
     * 删除分类（逻辑删除）：仅从字典隐身，<b>不动历史作品的 category 文本</b>；幂等。
     *
     * <p>这是刻意的设计而非遗漏：运营删的是「以后还能选这个名字」，不是「把已有作品重新归类」。
     * 真要批量改归类得另做一份迁移，不该藏在删除按钮里。
     *
     * @param type 同 {@link #updateCategory}，用于跨板块越权校验
     */
    void deleteCategory(String type, Long id, Long operatorId);
}
