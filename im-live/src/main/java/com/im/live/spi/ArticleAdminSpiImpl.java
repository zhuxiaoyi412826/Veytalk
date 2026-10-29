package com.im.live.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminArticleDTO;
import com.im.common.domain.AdminArticleQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminArticleSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.live.entity.Article;
import com.im.live.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台文章管理契约的实现（{@link AdminArticleSpi}）。
 *
 * <p>类名带模块前缀的理由见 {@code AdminMetricsSpi} 接口注释——同接口的实现类在全上下文里必须类名唯一，
 * 否则扫包时 {@code ConflictingBeanDefinitionException}。
 *
 * <p>与用户端 {@code ArticleServiceImpl} 各自分页而不共享方法：那边广场恒定 status=2 且要补「是否本人」，
 * 这边要全状态、能代写与越权改删，两套口径硬拧成一个方法迟早改一边坏一边。
 *
 * <p>状态流转的合法性在这里钉死：审核只在待审(1)↔已发布(2)/驳回(3) 之间动，上下架只在已发布(2)↔已下架(4)
 * 之间动；对不合法的当前态直接拒绝而不是强改，避免后台并发点出「已发布又被驳回」这类脏流转。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleAdminSpiImpl implements AdminArticleSpi {

    /** 单页上限：后台一屏最多 100 行，与 AdminUserSpi 的约定同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 关键词长度上限，防止一条 10KB 的 like 拖死全表扫 */
    private static final int KEYWORD_MAX = 50;
    private static final String DEFAULT_TITLE = "未命名文章";
    private static final int TITLE_MAX = 120;
    private static final int REASON_MAX = 255;

    private final ArticleMapper articleMapper;
    private final UserQuerySpi userQuerySpi;

    @Override
    public PageResult<AdminArticleDTO> pageArticles(AdminArticleQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = TextUtil.isBlank(query.getKeyword()) ? null : query.getKeyword().trim();
        if (keyword != null && keyword.length() > KEYWORD_MAX) {
            keyword = keyword.substring(0, KEYWORD_MAX);
        }

        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .eq(query.getStatus() != null, Article::getStatus, query.getStatus())
                .eq(query.getAuthorId() != null, Article::getAuthorId, query.getAuthorId())
                .orderByDesc(Article::getCreateTime);
        if (keyword != null) {
            // 数字关键词额外认「作者 ID 精确匹配」：运营从工单里拿到的是 uid，不会去猜昵称
            final String kw = keyword;
            boolean numeric = kw.chars().allMatch(Character::isDigit);
            wrapper.and(w -> {
                w.like(Article::getTitle, kw);
                if (numeric) {
                    w.or().eq(Article::getAuthorId, Long.parseLong(kw));
                }
            });
        }
        Page<Article> result = articleMapper.selectPage(new Page<>(current, size), wrapper);
        List<Article> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> authorIds = new HashSet<>();
        for (Article article : records) {
            if (article.getAuthorId() != null) {
                authorIds.add(article.getAuthorId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(authorIds);
        List<AdminArticleDTO> dtos = new ArrayList<>(records.size());
        for (Article article : records) {
            // 列表不带正文：toDTO(article, brief, false)
            dtos.add(toDTO(article, briefs.get(article.getAuthorId()), false));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminArticleDTO getDetail(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        return toDTO(article, briefOf(article.getAuthorId()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminArticleDTO create(AdminArticleDTO dto, Long operatorId, boolean publishNow) {
        Article article = new Article();
        article.setAuthorId(dto.getAuthorId() == null ? operatorId : dto.getAuthorId());
        article.setTitle(resolveTitle(dto.getTitle()));
        article.setSummary(TextUtil.isBlank(dto.getSummary()) ? "" : dto.getSummary().trim());
        article.setContent(dto.getContent() == null ? "" : dto.getContent());
        // 管理员代写不落封面 fileId：只存展示用的受控地址（封面本体由前端先用 article_cover 上传）
        article.setCoverUrl(TextUtil.isBlank(dto.getCoverUrl()) ? null : dto.getCoverUrl().trim());
        article.setViewCount(0);
        article.setStatus(publishNow ? Article.STATUS_PUBLISHED : Article.STATUS_PENDING);
        article.setRejectReason(null);
        article.setPublishTime(publishNow ? LocalDateTime.now() : null);
        articleMapper.insert(article);
        log.info("[后台文章管理] operator={} 代写文章: articleId={}, authorId={}, status={}",
                operatorId, article.getId(), article.getAuthorId(), article.getStatus());
        return toDTO(article, briefOf(article.getAuthorId()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminArticleDTO update(AdminArticleDTO dto, Long operatorId) {
        Article article = articleMapper.selectById(dto.getId());
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        if (!TextUtil.isBlank(dto.getTitle())) {
            article.setTitle(resolveTitle(dto.getTitle()));
        }
        if (dto.getSummary() != null) {
            article.setSummary(dto.getSummary().trim());
        }
        if (dto.getContent() != null) {
            article.setContent(dto.getContent());
        }
        if (dto.getCoverUrl() != null) {
            article.setCoverUrl(TextUtil.isBlank(dto.getCoverUrl()) ? null : dto.getCoverUrl().trim());
        }
        // 编辑不动状态：状态是审核/上下架的事，混进来会让「改个错别字」意外触发发布或下架
        articleMapper.updateById(article);
        log.info("[后台文章管理] operator={} 编辑文章: articleId={}", operatorId, article.getId());
        return toDTO(article, briefOf(article.getAuthorId()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminArticleDTO review(Long id, boolean approved, String reason, Long operatorId) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        int status = nz(article.getStatus());
        if (status != Article.STATUS_PENDING && status != Article.STATUS_DRAFT) {
            // 只对待审（容错也接受草稿）做审核裁决；已发布/驳回/下架再点「通过」是误操作
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅待审核的文章可通过或驳回");
        }
        int target = approved ? Article.STATUS_PUBLISHED : Article.STATUS_REJECTED;
        Article patch = new Article();
        patch.setId(article.getId());
        patch.setStatus(target);
        if (approved) {
            patch.setPublishTime(article.getPublishTime() == null ? LocalDateTime.now() : article.getPublishTime());
            patch.setRejectReason("");
        } else {
            patch.setRejectReason(truncate(reason, REASON_MAX));
        }
        articleMapper.updateById(patch);
        article.setStatus(target);
        if (approved) {
            article.setPublishTime(patch.getPublishTime());
            article.setRejectReason(null);
        } else {
            article.setRejectReason(patch.getRejectReason());
        }
        log.info("[后台文章管理] operator={} 将文章 {} 审核为{}: reason={}",
                operatorId, id, approved ? "通过" : "驳回", TextUtil.isBlank(reason) ? "—" : reason);
        return toDTO(article, briefOf(article.getAuthorId()), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminArticleDTO setOffline(Long id, boolean offline, Long operatorId, String reason) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        int status = nz(article.getStatus());
        int target;
        if (offline) {
            if (status != Article.STATUS_PUBLISHED) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅已发布的文章可下架");
            }
            target = Article.STATUS_OFFLINE;
        } else {
            if (status != Article.STATUS_OFFLINE) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅已下架的文章可恢复上架");
            }
            target = Article.STATUS_PUBLISHED;
        }
        if (status != target) {
            Article patch = new Article();
            patch.setId(article.getId());
            patch.setStatus(target);
            articleMapper.updateById(patch);
            article.setStatus(target);
        }
        log.info("[后台文章管理] operator={} 将文章 {} 置为{}: reason={}",
                operatorId, id, offline ? "下架" : "恢复上架", TextUtil.isBlank(reason) ? "—" : reason);
        return toDTO(article, briefOf(article.getAuthorId()), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            // 幂等：本人已删过的行再删一次不报错
            return;
        }
        articleMapper.deleteById(id);
        log.info("[后台文章管理] operator={} 删除文章: id={}, authorId={}, title={}",
                operatorId, id, article.getAuthorId(), article.getTitle());
    }

    private UserBriefDTO briefOf(Long userId) {
        return userId == null ? null : userQuerySpi.getById(userId);
    }

    private String resolveTitle(String title) {
        String value = TextUtil.isBlank(title) ? DEFAULT_TITLE : title.trim();
        return truncate(value, TITLE_MAX);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 实体 → 后台 DTO。{@code withContent=true} 才带正文（详情/写回用），分页传 false。
     */
    private AdminArticleDTO toDTO(Article article, UserBriefDTO author, boolean withContent) {
        String name = author == null ? "已注销用户"
                : (TextUtil.isNotBlank(author.getNickname()) ? author.getNickname() : author.getUsername());
        AdminArticleDTO.AdminArticleDTOBuilder builder = AdminArticleDTO.builder()
                .id(article.getId())
                .authorId(article.getAuthorId())
                .authorName(name)
                .title(article.getTitle())
                .summary(article.getSummary())
                .coverUrl(article.getCoverUrl())
                .status(article.getStatus())
                .rejectReason(article.getRejectReason())
                .viewCount(article.getViewCount() == null ? 0L : article.getViewCount().longValue())
                .publishTime(article.getPublishTime())
                .createTime(article.getCreateTime());
        if (withContent) {
            builder.content(article.getContent());
        }
        return builder.build();
    }
}
