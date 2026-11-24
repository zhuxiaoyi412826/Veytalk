package com.im.live.service;

import com.im.live.entity.LiveVideoCategory;

import java.util.List;

/**
 * 板块二级分类字典服务：给用户端分类栏提供启用名称，给后台提供增删改查。
 *
 * <p>一侧是「所有人进板块都要拉」的只读列表（{@link #enabledNames(String)}，只回启用项、按 sort 升序），
 * 一侧是「运营在后台维护」的全量 CRUD（含停用项）。两者口径不同故分开：用户端不该看到停用的分类，
 * 后台要能看到并切换它们。后台 CRUD 经 {@code AdminWorkCategorySpi} 转调本服务
 * （星型架构，im-admin 不碰本表）。
 *
 * <p><b>每个方法都带 {@code type}</b>：{@code im_live_video_category} 一张表吃四个板块
 * （video / image / music / novel），同名校验、排序起点、以及「改的是不是自己那一屏的字典」
 * 都要按板块成立。少一个参数就会出现「图片管理员改到了小说板块的分类」这种越权，
 * 而它在界面上完全看不出来。
 *
 * <p>{@link #enabledNames()} 这个无参重载是给视频板块留的兼容入口（固定 {@code video}），
 * 新代码一律走带 {@code type} 的版本。
 */
public interface LiveVideoCategoryService {

    /** 用户端分类栏：仅启用（status=1）的分类名称，按 sort 升序。固定视频板块，新调用请用下面的重载 */
    List<String> enabledNames();

    /**
     * 用户端分类栏：指定板块的启用分类名称，按 sort 升序。
     *
     * @param type video / image / music / novel，未知值拒绝
     */
    List<String> enabledNames(String type);

    /** 后台：指定板块的全部分类（含停用），按 sort 升序 */
    List<LiveVideoCategory> listAll(String type);

    /**
     * 后台新增分类。
     *
     * @param type 归属板块，由服务端写死来源（用户端接口不接前端传值）
     * @param name 分类名（trim 后非空、≤32、在<b>同板块</b>未删除行内唯一）
     * @param sort 排序，null 时排到最后（该板块当前最大 sort + 1）
     * @return 落库后的那一行
     */
    LiveVideoCategory create(String type, String name, Integer sort, Long operatorId);

    /**
     * 后台修改分类：改名 / 调序 / 启停。<b>不允许改板块</b>——把一行挪到另一个板块，等于把它名下
     * 所有作品的分类文本变成孤儿。
     *
     * @param type 调用方声明的板块；与行上的 {@code type} 不一致时拒绝（防跨板块越权改字典）
     * @param status 目标状态（1 启用 0 停用），null 视为不改状态
     */
    LiveVideoCategory update(String type, Long id, String name, Integer sort, Integer status, Long operatorId);

    /**
     * 后台删除（逻辑删除）：仅让分类从字典隐身，不动任何已发布作品的 category 文本；幂等。
     *
     * @param type 调用方声明的板块，同样用于跨板块越权校验
     */
    void delete(String type, Long id, Long operatorId);
}
