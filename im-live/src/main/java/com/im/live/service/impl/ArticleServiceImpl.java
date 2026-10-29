package com.im.live.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.live.dto.req.ArticleSaveRequest;
import com.im.live.dto.vo.ArticleVO;
import com.im.live.entity.Article;
import com.im.live.mapper.ArticleMapper;
import com.im.live.service.ArticleService;
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
 * 文章板块服务实现（作者侧）。
 *
 * <p>正文里的图片与封面本体的访问控制在 {@code im-file}：上传时打了 {@code article_img}/{@code article_cover}
 * 的公开 bizType，{@code FileServiceImpl#canAccess} 对所有登录用户放行，因此列表/详情返回的受控地址任何人都能拉。
 *
 * <p>受控地址由服务端按 {@code im.file.access-url-prefix} 现拼（{@link #accessUrl}）而不是采信前端传来的 URL：
 * 前端只能决定「我引用哪个 fileId」，拼出来的地址永远是本站的受控下载地址，杜绝客户端塞一条外部直链进来。
 *
 * <p>状态机是本类的主线：作者只能在 {@code 草稿(0)/驳回(3)} 之间编辑并按 {@code action} 落 {@code 待审(1)} 或
 * 维持草稿，永远不能把自己文章直接写成 {@code 已发布(2)}——发布只能由管理员经 {@code AdminArticleSpi} 触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private static final String DEFAULT_TITLE = "未命名文章";
    private static final int TITLE_MAX = 120;
    private static final int SUMMARY_MAX = 255;
    /** 摘要缺省时从正文截取的长度 */
    private static final int DERIVED_SUMMARY_MAX = 100;

    private final ArticleMapper articleMapper;
    private final UserQuerySpi userQuerySpi;
    private final ImProperties imProperties;

    @Override
    public Page<ArticleVO> pagePublic(long current, long size) {
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .eq(Article::getStatus, Article.STATUS_PUBLISHED)
                .orderByDesc(Article::getCreateTime);
        return pageToVO(new Page<>(current, size), wrapper, SecurityUtil.getUserIdOrNull());
    }

    @Override
    public Page<ArticleVO> pageMine(long current, long size) {
        Long userId = SecurityUtil.getUserId();
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .eq(Article::getAuthorId, userId)
                .orderByDesc(Article::getCreateTime);
        return pageToVO(new Page<>(current, size), wrapper, userId);
    }

    @Override
    public ArticleVO detail(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        Long me = SecurityUtil.getUserIdOrNull();
        boolean published = Integer.valueOf(Article.STATUS_PUBLISHED).equals(article.getStatus());
        // 已发布对任何人可见；未发布仅本人与管理员可见——草稿/待审/驳回/下架都不该被外人读到
        if (!published && !article.ownedBy(me) && !SecurityUtil.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        // 只有对外可见的已发布文章才计浏览；作者/管理员预览草稿不污染计数
        if (published) {
            increaseViewCount(id);
            article.setViewCount(nz(article.getViewCount()) + 1);
        }
        return toVO(article, briefOf(article.getAuthorId()), me, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleVO create(ArticleSaveRequest request) {
        Long userId = SecurityUtil.getUserId();
        Article article = new Article();
        article.setAuthorId(userId);
        applyContent(article, request);
        article.setViewCount(0);
        article.setStatus(submitting(request) ? Article.STATUS_PENDING : Article.STATUS_DRAFT);
        article.setRejectReason(null);
        article.setPublishTime(null);
        articleMapper.insert(article);
        log.info("新建文章: articleId={}, authorId={}, status={}", article.getId(), userId, article.getStatus());
        return toVO(article, userQuerySpi.getById(userId), userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleVO update(Long id, ArticleSaveRequest request) {
        Long userId = SecurityUtil.getUserId();
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        if (!article.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        int status = nz(article.getStatus());
        // 只有草稿与驳回态可编辑；待审(在审核队列里)与已发布/已下架都不允许作者自己改内容
        if (status != Article.STATUS_DRAFT && status != Article.STATUS_REJECTED) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "当前状态的文章不可编辑");
        }
        applyContent(article, request);
        article.setStatus(submitting(request) ? Article.STATUS_PENDING : Article.STATUS_DRAFT);
        // 重新送审即清掉上一次的驳回理由
        article.setRejectReason(null);
        articleMapper.updateById(article);
        log.info("编辑文章: articleId={}, authorId={}, status={}", id, userId, article.getStatus());
        return toVO(article, userQuerySpi.getById(userId), userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArticleVO submit(Long id) {
        Long userId = SecurityUtil.getUserId();
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在或已被删除");
        }
        if (!article.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        int status = nz(article.getStatus());
        if (status != Article.STATUS_DRAFT && status != Article.STATUS_REJECTED) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "仅草稿或已驳回的文章可提交审核");
        }
        Article patch = new Article();
        patch.setId(article.getId());
        patch.setStatus(Article.STATUS_PENDING);
        patch.setRejectReason("");
        articleMapper.updateById(patch);
        article.setStatus(Article.STATUS_PENDING);
        log.info("提交文章审核: articleId={}, authorId={}", id, userId);
        return toVO(article, userQuerySpi.getById(userId), userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            // 幂等：重复删除或已被他人删除时静默返回
            return;
        }
        Long userId = SecurityUtil.getUserId();
        if (!article.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        // @TableLogic 逻辑删除：广场与详情即刻消失，im_file 里的图片本体保留（可能被别处引用/便于恢复）
        articleMapper.deleteById(id);
        log.info("删除文章: articleId={}, authorId={}", id, userId);
    }

    /**
     * 分页查询 → VO：批量补作者昵称/头像，列表不带正文（{@code withContent=false}）。
     */
    private Page<ArticleVO> pageToVO(Page<Article> page, LambdaQueryWrapper<Article> wrapper, Long me) {
        Page<Article> result = articleMapper.selectPage(page, wrapper);
        Page<ArticleVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<Article> records = result.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Set<Long> authorIds = new HashSet<>();
        for (Article article : records) {
            authorIds.add(article.getAuthorId());
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(authorIds);
        List<ArticleVO> vos = new ArrayList<>(records.size());
        for (Article article : records) {
            vos.add(toVO(article, briefs.get(article.getAuthorId()), me, false));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    /**
     * 把请求里的标题/摘要/正文/封面落到实体，含兜底与截断、封面受控地址现拼。
     */
    private void applyContent(Article article, ArticleSaveRequest request) {
        article.setTitle(resolveTitle(request.getTitle()));
        article.setContent(request.getContent());
        String summary = TextUtil.isBlank(request.getSummary())
                ? deriveSummary(request.getContent()) : request.getSummary().trim();
        article.setSummary(truncate(summary, SUMMARY_MAX));
        if (request.getCoverFileId() != null) {
            article.setCoverFileId(request.getCoverFileId());
            article.setCoverUrl(accessUrl(request.getCoverFileId()));
        } else {
            article.setCoverFileId(null);
            article.setCoverUrl(null);
        }
    }

    private boolean submitting(ArticleSaveRequest request) {
        return ArticleSaveRequest.ACTION_SUBMIT.equalsIgnoreCase(request.getAction());
    }

    private void increaseViewCount(Long id) {
        // setSql 原子自增，不经「读-改-写」内存值，避免并发丢计数；这是展示统计，非强一致
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .setSql("view_count = view_count + 1"));
    }

    private String resolveTitle(String title) {
        String value = TextUtil.isBlank(title) ? DEFAULT_TITLE : title.trim();
        return truncate(value, TITLE_MAX);
    }

    /**
     * 摘要兜底：去掉 Markdown 常见符号与换行，压成一行纯文本前缀，供列表卡片展示。
     * 不追求完美排版，只求「别把 # ** - 这些标记原样糊在摘要里」。
     */
    private String deriveSummary(String content) {
        if (TextUtil.isBlank(content)) {
            return "";
        }
        String plain = content
                .replaceAll("`{1,3}", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")   // 图片
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1") // 链接留文字
                .replaceAll("^\\s{0,3}#{1,6}\\s*", "")       // 标题
                .replaceAll("[*_-]{1,}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return truncate(plain, DERIVED_SUMMARY_MAX);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String accessUrl(Long fileId) {
        return imProperties.getFile().getAccessUrlPrefix() + "/" + fileId;
    }

    private UserBriefDTO briefOf(Long userId) {
        return userId == null ? null : userQuerySpi.getById(userId);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 实体 → VO。{@code withContent=false} 时不带正文（列表用），{@code true} 时带（详情/写回用）。
     */
    private ArticleVO toVO(Article article, UserBriefDTO brief, Long me, boolean withContent) {
        ArticleVO vo = new ArticleVO();
        vo.setId(article.getId());
        vo.setAuthorId(article.getAuthorId());
        if (brief != null) {
            vo.setAuthorName(brief.getNickname());
            vo.setAuthorAvatar(brief.getAvatar());
        }
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        if (withContent) {
            vo.setContent(article.getContent());
        }
        vo.setCoverUrl(article.getCoverUrl());
        vo.setCoverFileId(article.getCoverFileId());
        vo.setStatus(article.getStatus());
        vo.setRejectReason(article.getRejectReason());
        vo.setViewCount(article.getViewCount());
        vo.setPublishTime(article.getPublishTime());
        vo.setCreateTime(article.getCreateTime());
        vo.setMine(article.ownedBy(me));
        return vo;
    }

    /** 供 SPI 实现复用的发布时间戳 */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
