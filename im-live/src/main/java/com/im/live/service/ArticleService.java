package com.im.live.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.live.dto.req.ArticleSaveRequest;
import com.im.live.dto.vo.ArticleVO;

/**
 * 直播页「文章」板块服务（作者侧）：广场分页、我的分页、详情、新建、编辑、删除与提交审核。
 *
 * <p>与后台的 {@code AdminArticleSpi} 各管各的口径：本服务面向登录用户，广场只吐 status=2、
 * 「我的」吐本人全部状态、编辑严格限本人且限草稿/驳回态；后台那套看全状态、能代写与越权改删。
 * 两者不复用方法语义，避免把「仅本人可编辑」的校验绕进共用路径。
 */
public interface ArticleService {

    /**
     * 广场分页：仅 {@code status=2} 的已发布文章，按创建时间倒序；批量补全作者昵称/头像，不含正文。
     */
    Page<ArticleVO> pagePublic(long current, long size);

    /**
     * 我的文章：当前登录用户所写，含全部状态（草稿/待审/驳回/已发布/已下架），按创建时间倒序，不含正文。
     */
    Page<ArticleVO> pageMine(long current, long size);

    /**
     * 详情：含正文。已发布对任何人可见；未发布仅本人与管理员可见，否则 {@code FORBIDDEN}。
     * 命中已发布文章时对 {@code view_count} 原子自增。
     */
    ArticleVO detail(Long id);

    /**
     * 新建：作者=登录用户，{@code action=submit} 落待审(1) 否则落草稿(0)；服务端拼封面受控地址。
     */
    ArticleVO create(ArticleSaveRequest request);

    /**
     * 编辑：仅本人且状态为草稿(0)/驳回(3) 可改；{@code action=submit} 改完重新送审(1)，否则维持草稿(0)。
     */
    ArticleVO update(Long id, ArticleSaveRequest request);

    /**
     * 提交审核：把本人草稿(0)/驳回(3) 的文章置为待审(1)。非法状态或越权报错。
     */
    ArticleVO submit(Long id);

    /**
     * 删除（逻辑删除）：仅作者本人可删，否则 {@code FORBIDDEN}；记录不存在幂等返回。
     */
    void delete(Long id);
}
