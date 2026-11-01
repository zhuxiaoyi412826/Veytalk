package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminArticleDTO;
import com.im.common.domain.AdminArticleQueryDTO;

/**
 * 后台文章管理契约，由 im-live 实现（{@code ArticleAdminSpiImpl}）。
 *
 * <p>星型架构老问题：im-admin 不能 import {@code ArticleMapper}，但「翻全部文章（含草稿/待审）、
 * 审核通过/驳回、上下架、以管理员身份新建/编辑/删除」这些事只有文章所在的 im-live 能做，
 * 于是契约反转给它。
 *
 * <p>与用户端 {@code ArticleService} 的边界：用户端广场只看得到 status=2、作者只能改自己的草稿/驳回，
 * 后台看得到全状态、能代写与越权改删——所以不复用那个 Service 的方法语义，实现类直接对 mapper 说话。
 *
 * <p>实现方注意：本接口被调到时 im-live 必然在场（同进程装配），但 im-admin 侧用
 * {@code ObjectProvider} 消费——没装该模块时后台显示「模块未部署」，而不是 500。
 */
public interface AdminArticleSpi {

    /**
     * 翻页查文章，<b>含全状态</b>；排序写死创建时间倒序。分页上限与关键词长度由实现方自己夹。
     * 返回的列表项不含正文（content 为 null）。
     */
    PageResult<AdminArticleDTO> pageArticles(AdminArticleQueryDTO query);

    /**
     * 取单篇详情（含正文 content），供后台预览与编辑。记录不存在抛业务异常。
     */
    AdminArticleDTO getDetail(Long id);

    /**
     * 管理员代写一篇文章。
     *
     * @param dto        至少含 title/content，summary/coverUrl 可选；authorId 为空则记为操作者本人
     * @param operatorId 操作管理员，进审计日志
     * @param publishNow {@code true} 直接落 status=2（官方内容免审发布），{@code false} 落 status=1（走审核）
     * @return 落库后的文章档案（含正文）
     */
    AdminArticleDTO create(AdminArticleDTO dto, Long operatorId, boolean publishNow);

    /**
     * 管理员编辑任意文章（改标题/摘要/正文/封面）。不借编辑改状态——状态走 review/setOffline。
     * 记录不存在抛业务异常。
     */
    AdminArticleDTO update(AdminArticleDTO dto, Long operatorId);

    /**
     * 审核：{@code approved=true} 置 status=2 并盖发布时间；{@code false} 置 status=3 并写 rejectReason。
     * 幂等：目标状态与当前一致时直接返回当前档案。记录不存在抛业务异常。
     */
    AdminArticleDTO review(Long id, boolean approved, String reason, Long operatorId);

    /**
     * 上架/下架：{@code offline=true} 由 status=2 置 status=4；{@code false} 由 status=4 恢复为 status=2。
     * 幂等。记录不存在抛业务异常。
     */
    AdminArticleDTO setOffline(Long id, boolean offline, Long operatorId, String reason);

    /**
     * 以管理员身份删除（逻辑删除，deleted=1）。幂等：记录不存在时静默返回。
     */
    void delete(Long id, Long operatorId);
}
