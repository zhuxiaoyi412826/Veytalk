package com.im.live.service;

import com.im.live.entity.LiveVideoCategory;

import java.util.List;

/**
 * 视频板块二级分类字典服务：给用户端分类栏提供启用名称，给后台提供增删改查。
 *
 * <p>一侧是「所有人进视频板块都要拉」的只读列表（{@link #enabledNames}，只回启用项、按 sort 升序），
 * 一侧是「运营在后台维护」的全量 CRUD（含停用项）。两者口径不同故分开：用户端不该看到停用的分类，
 * 后台要能看到并切换它们。后台 CRUD 经 {@code AdminVideoSpi} 转调本服务（星型架构，im-admin 不碰本表）。
 */
public interface LiveVideoCategoryService {

    /** 用户端分类栏：仅启用（status=1）的分类名称，按 sort 升序 */
    List<String> enabledNames();

    /** 后台：全部分类（含停用），按 sort 升序 */
    List<LiveVideoCategory> listAll();

    /**
     * 后台新增分类。
     *
     * @param name 分类名（trim 后非空、≤32、在未删除行内唯一）
     * @param sort 排序，null 时排到最后（当前最大 sort + 1）
     * @return 落库后的那一行
     */
    LiveVideoCategory create(String name, Integer sort, Long operatorId);

    /**
     * 后台修改分类：改名 / 调序 / 启停。
     *
     * @param status 目标状态（1 启用 0 停用），null 视为不改状态
     */
    LiveVideoCategory update(Long id, String name, Integer sort, Integer status, Long operatorId);

    /** 后台删除（逻辑删除）：仅让分类从字典隐身，不动任何已发布视频的 category 文本；幂等 */
    void delete(Long id, Long operatorId);
}
