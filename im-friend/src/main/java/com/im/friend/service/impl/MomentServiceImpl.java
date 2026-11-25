package com.im.friend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.FileDTO;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.MomentCardDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.MomentVisibility;
import com.im.common.enums.MomentVisibleDays;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.spi.DatingQuerySpi;
import com.im.common.spi.FileStorageSpi;
import com.im.common.spi.IntimacySpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.friend.dto.req.MomentCommentRequest;
import com.im.friend.dto.req.MomentMediaItem;
import com.im.friend.dto.req.MomentMineQuery;
import com.im.friend.dto.req.MomentPublishRequest;
import com.im.friend.dto.req.MomentShareRequest;
import com.im.friend.dto.vo.MomentCommentVO;
import com.im.friend.dto.vo.MomentMediaVO;
import com.im.friend.dto.vo.MomentShareVO;
import com.im.friend.dto.vo.MomentVO;
import com.im.friend.entity.Friend;
import com.im.friend.entity.MatchPair;
import com.im.friend.entity.Moment;
import com.im.friend.entity.MomentComment;
import com.im.friend.entity.MomentLike;
import com.im.friend.entity.MomentMedia;
import com.im.friend.entity.MomentVisible;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.mapper.MatchPairMapper;
import com.im.friend.mapper.MomentCommentMapper;
import com.im.friend.mapper.MomentLikeMapper;
import com.im.friend.mapper.MomentMapper;
import com.im.friend.mapper.MomentMediaMapper;
import com.im.friend.mapper.MomentVisibleMapper;
import com.im.friend.service.MomentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态 Feed 实现。
 *
 * <p>本类只碰 {@code im_moment*} 五张表与 {@code im_friend} / {@code im_match_pair} 两张关系表。
 * 资料一律经 {@link DatingQuerySpi}（「几天可见」那一列住在 im-user 的 {@code im_user_profile} 上），
 * 昵称头像经 {@link UserQuerySpi}，文件经 {@link FileStorageSpi}，发消息经 {@link MessageSpi}。
 *
 * <h2>为什么可见范围要在内存里过滤，而不是写进 SQL</h2>
 *
 * <p>三档判据不同源：{@code PUBLIC} 与 {@code FRIENDS_ONLY} 看的是关系表，{@code PARTIAL} 看的是
 * 白名单表，而「几天可见」看的是<b>另一个模块</b>的一张资料表。合成一句 SQL 要跨模块 join，
 * 星型架构下做不到；写成 CASE 则是一句没人敢改的东西。所以这里的口径是：
 * <b>SQL 只负责按受众与游标取候选，可见范围由服务层一次批量查完再逐条判</b>。
 * 代价是候选要多取几倍（{@link #FILL_ROUNDS}），换来的是判据只有一处。
 *
 * <h2>为什么点赞与评论的加分挂在事务提交之后</h2>
 *
 * <p>亲密度是一张尽力而为的计数表，它自己内部吞异常（见 {@link IntimacySpi} 的类注释）。
 * 但「事务回滚了分却加上了」是另一回事：那会让一个失败的请求留下痕迹。所以统一走
 * {@link #afterCommit}，与 {@code MatchServiceImpl} 的推送同一口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomentServiceImpl implements MomentService {

    /** Feed 一页默认与上限条数：动态是逐条读的流，一次给 50 条只是把流量浪费在划不到的内容上 */
    private static final int FEED_DEFAULT_SIZE = 10;
    private static final int FEED_MAX_SIZE = 30;

    /**
     * 候选被可见范围滤掉时的补捞轮数。
     *
     * <p>一轮取的候选可能整批都是别人「仅自己」的动态，这时候返回 0 条并不代表刷到底了。
     * 补三轮是一个折中：绝大多数情况下第一轮就能填满，而真被滤空三轮说明这个人的受众里
     * 本来就没什么可看的，再捞只是白烧 SQL。
     */
    private static final int FILL_ROUNDS = 3;

    /**
     * 受众（{@code IN} 列表）的上限。
     *
     * <p>超出时按 ID 倒序保留最近的 {@value} 个：ID 大的是新建立的关系，而 Feed 上
     * 「新认识的人」比「三年前的老友」更该出现。被挤掉的人的动态<b>仍然能在他的主页上看到</b>
     * （{@link #userMoments} 是按单个作者查的，不走这个 {@code IN}），所以这不是内容丢失，
     * 只是 Feed 少几条。真到需要放开这一天时，该改的是取数方式（按时间分片），不是这个常量。
     */
    private static final int AUDIENCE_MAX = 1000;

    /** 卡片上的正文摘要长度：气泡就那么宽，再长也是被前端截掉 */
    private static final int SUMMARY_MAX = 60;

    private final MomentMapper momentMapper;
    private final MomentMediaMapper momentMediaMapper;
    private final MomentVisibleMapper momentVisibleMapper;
    private final MomentLikeMapper momentLikeMapper;
    private final MomentCommentMapper momentCommentMapper;
    private final FriendMapper friendMapper;
    private final MatchPairMapper matchPairMapper;
    private final DatingQuerySpi datingQuerySpi;
    private final UserQuerySpi userQuerySpi;
    private final SensitiveWordFilter sensitiveWordFilter;
    /** 文件元数据与受控地址只能经文件模块取；im-file 缺席时报「存储服务不可用」而不是谎称文件不存在 */
    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;
    /** 点赞与评论要加亲密度；im-friend 自己实现它，但仍用弱依赖避开构造期的自引用 */
    private final ObjectProvider<IntimacySpi> intimacySpiProvider;
    /** 动态转聊天；im-message 缺席时动态照常发布与浏览，只是转不出去 */
    private final ObjectProvider<MessageSpi> messageSpiProvider;

    /* ==================== 发布 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MomentVO publish(Long userId, MomentPublishRequest request) {
        MomentVisibility scope = MomentVisibility.of(request.getVisibility());
        // 脏值不退到「公开」：这是隐私位，认不出来就拒绝写入，而不是替用户选一个最宽的档位
        BusinessException.throwIf(scope == null, ResultCode.BAD_REQUEST, "可见范围不合法");

        String content = sensitiveWordFilter.mask(TextUtil.sanitize(request.getContent()));
        List<MomentMediaItem> media = request.getMedia() == null ? List.of() : request.getMedia();
        // 先 sanitize 再判空：一段只由标签组成的正文清洗完就是空的，那等于没写字
        BusinessException.throwIf(TextUtil.isBlank(content) && media.isEmpty(), ResultCode.MOMENT_CONTENT_REQUIRED);
        checkMediaShape(media);

        List<Long> whitelist = scope.needsWhitelist()
                ? normalizeWhitelist(request.getVisibleUserIds(), userId) : List.of();
        BusinessException.throwIf(scope.needsWhitelist() && whitelist.isEmpty(), ResultCode.MOMENT_VISIBLE_REQUIRED);

        Moment moment = new Moment();
        moment.setUserId(userId);
        moment.setContent(TextUtil.isBlank(content) ? null : content.trim());
        moment.setVisibility(scope.getCode());
        moment.setMediaCount(media.size());
        moment.setLikeCount(0);
        moment.setCommentCount(0);
        moment.setStatus(Moment.STATUS_NORMAL);
        momentMapper.insert(moment);

        insertMedia(moment.getId(), userId, media);
        insertWhitelist(moment.getId(), whitelist);
        return render(List.of(moment), userId).get(0);
    }

    /**
     * 附件的组合约束：9 张图<b>或</b> 1 个视频，两者不混排。
     *
     * <p>不混排不是审美问题：九宫格的版式按等比缩略图画，一个视频格子进去就要单独占一整行，
     * 而「一条动态里既有图又有视频」在前端要同时准备两套播放器与两套占位，
     * 换来的是一个几乎没人用的组合。
     */
    private void checkMediaShape(List<MomentMediaItem> media) {
        if (media.isEmpty()) {
            return;
        }
        long videos = media.stream()
                .filter(item -> item.getType() != null && item.getType() == MomentMedia.TYPE_VIDEO)
                .count();
        boolean illegal = videos > Moment.MAX_VIDEOS
                || (videos > 0 && media.size() > Moment.MAX_VIDEOS)
                || (videos == 0 && media.size() > Moment.MAX_IMAGES);
        BusinessException.throwIf(illegal, ResultCode.MOMENT_MEDIA_LIMIT, Moment.MAX_IMAGES);
    }

    /**
     * 白名单归一：去重、去掉自己、去掉我拉黑的人，并按 {@link MomentPublishRequest#WHITELIST_MAX} 夹一刀。
     *
     * <p>去掉自己是因为作者恒可见（{@link Moment#visibleTo} 的第一条），写进去只会多一行没人读的记录；
     * 去掉拉黑的人是因为「我不想再看到这个人」是最强的表达，它不该被一次点名覆盖。
     */
    private List<Long> normalizeWhitelist(List<Long> raw, Long userId) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Long> blocked = new HashSet<>(friendMapper.selectBlockedFriendIds(userId));
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id == null || id.equals(userId) || blocked.contains(id)) {
                continue;
            }
            result.add(id);
            if (result.size() >= MomentPublishRequest.WHITELIST_MAX) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 写附件行：地址由服务端现拼，前端传什么 URL 都不算数。
     *
     * <p>三重闸门（文件存在 + 本人上传 + {@code bizType} 与类型对得上）漏掉后两条，
     * 就能拿别人的照片墙图片或聊天图冒充成自己的动态附图——而动态是好友与配对对象都会看到的位置。
     */
    private void insertMedia(Long momentId, Long userId, List<MomentMediaItem> media) {
        if (media.isEmpty()) {
            return;
        }
        FileStorageSpi storage = requireStorage();
        Set<Long> seen = new HashSet<>();
        int sort = 0;
        for (MomentMediaItem item : media) {
            FileDTO file = storage.getById(item.getFileId());
            boolean video = item.getType() != null && item.getType() == MomentMedia.TYPE_VIDEO;
            String expectedBiz = video ? MomentMedia.BIZ_VIDEO : MomentMedia.BIZ_IMAGE;
            BusinessException.throwUnless(file != null
                    && userId.equals(file.getUploaderId())
                    && expectedBiz.equals(file.getBizType()), ResultCode.MOMENT_MEDIA_FILE_INVALID);
            // 同一个文件在同一条动态里放两次只会让九宫格出现两格一样的图
            BusinessException.throwIf(!seen.add(file.getFileId()), ResultCode.MOMENT_MEDIA_FILE_INVALID);

            MomentMedia row = new MomentMedia();
            row.setMomentId(momentId);
            row.setFileId(file.getFileId());
            row.setUrl(storage.accessUrl(file.getFileId()));
            row.setType(item.getType());
            row.setSort(sort++);
            row.setWidth(item.getWidth());
            row.setHeight(item.getHeight());
            row.setDuration(video ? item.getDuration() : null);
            // 字节数取文件元数据，不信客户端上报
            row.setSize(file.getSize());
            momentMediaMapper.insert(row);
        }
    }

    /**
     * 写白名单行。
     *
     * <p>主键手工发号：{@link MomentVisibleMapper#insertBatch} 是自定义 SQL，
     * 拿不到 MyBatis-Plus 的自动填充（同 {@code MatchExposureMapper} 的约定）。
     */
    private void insertWhitelist(Long momentId, List<Long> whitelist) {
        if (whitelist.isEmpty()) {
            return;
        }
        List<MomentVisible> rows = new ArrayList<>(whitelist.size());
        for (Long targetId : whitelist) {
            MomentVisible row = new MomentVisible();
            row.setId(IdWorker.getId());
            row.setMomentId(momentId);
            row.setUserId(targetId);
            rows.add(row);
        }
        momentVisibleMapper.insertBatch(rows);
    }

    /* ==================== 读取 ==================== */

    @Override
    public List<MomentVO> feed(Long userId, Long cursorId, Integer size) {
        int want = normalizeSize(size);
        Viewer viewer = viewerOf(userId);
        Set<Long> audience = audienceOf(viewer);
        if (audience.isEmpty()) {
            return List.of();
        }
        int batch = Math.min(want * 3, MomentMapper.CANDIDATE_MAX);
        LocalDateTime now = LocalDateTime.now();
        List<Moment> picked = new ArrayList<>(want);
        Long cursor = cursorId;
        for (int round = 0; round < FILL_ROUNDS && picked.size() < want; round++) {
            List<Moment> candidates = momentMapper.selectFeedCandidates(audience, cursor, batch);
            if (candidates.isEmpty()) {
                break;
            }
            for (Moment moment : filterVisible(candidates, viewer, now)) {
                if (picked.size() >= want) {
                    break;
                }
                picked.add(moment);
            }
            // 游标推进到「扫过的最后一条」而不是「挑中的最后一条」：下一页从挑中的那条之后开始，
            // 被滤掉的行会再扫一次，但绝不会漏掉夹在中间的可见行
            cursor = candidates.get(candidates.size() - 1).getId();
            if (candidates.size() < batch) {
                break;
            }
        }
        return render(picked, userId);
    }

    @Override
    public List<MomentVO> userMoments(Long viewerId, Long targetUserId, Long cursorId, Integer size) {
        BusinessException.throwIf(targetUserId == null, ResultCode.BAD_REQUEST, "targetUserId 不能为空");
        int want = normalizeSize(size);
        boolean self = targetUserId.equals(viewerId);
        Viewer viewer = viewerOf(viewerId);
        if (!self) {
            // 我拉黑的人主页空空如也，不报错：报「无权查看」等于告诉他「这个人存在而且发过动态」
            if (viewer.blocked().contains(targetUserId)) {
                return List.of();
            }
        }
        // 看别人时按对方的偏好设时间下界；看自己不设，作者永远能翻到自己的全部历史
        LocalDateTime since = self ? null
                : MomentVisibleDays.of(datingQuerySpi.momentVisibleDays(List.of(targetUserId)).get(targetUserId))
                .cutoff(LocalDateTime.now());

        int batch = Math.min(want * 3, MomentMapper.CANDIDATE_MAX);
        List<Moment> picked = new ArrayList<>(want);
        Long cursor = cursorId;
        LocalDateTime now = LocalDateTime.now();
        for (int round = 0; round < FILL_ROUNDS && picked.size() < want; round++) {
            List<Moment> candidates = momentMapper.selectByUser(targetUserId, cursor, batch, self, since);
            if (candidates.isEmpty()) {
                break;
            }
            for (Moment moment : self ? candidates : filterVisible(candidates, viewer, now)) {
                if (picked.size() >= want) {
                    break;
                }
                picked.add(moment);
            }
            cursor = candidates.get(candidates.size() - 1).getId();
            if (candidates.size() < batch) {
                break;
            }
        }
        return render(picked, viewerId);
    }

    /**
     * 我的动态分页。
     *
     * <p>条件全部缺省时等价于「按发布时间倒序翻自己的历史」，而页码与 Feed 的游标不同，
     * 所以下一行取的是 {@link Page}：MyBatis-Plus 把记录与总数都填回同一个对象。
     *
     * <p>不认识的条件值一律<b>当没传</b>而不是报错：这一屏是辅助查看，前端控件递来一个脏值
     * （典型场景是版本没对齐、多了一个旧控件）时，空列表与 500 都不如「按无筛选返回」。
     * 但 {@code sortBy} 不认识时只回默认列，<b>不拼列名</b>：那一串字符串一旦能直达 SQL
     * 就是一个 ORDER BY 注入口。
     */
    @Override
    public PageResult<MomentVO> minePage(Long userId, MomentMineQuery query) {
        MomentMineQuery safe = query == null ? new MomentMineQuery() : query;
        String keyword = safe.getKeyword() == null || safe.getKeyword().isBlank()
                ? null : safe.getKeyword().trim();
        Page<Moment> page = safe.toPage();
        momentMapper.selectMinePage(page, userId, new MomentMapper.MineFilter(
                knownKind(safe.getContentType()),
                knownStatus(safe.getStatus()),
                safe.getStartDate() == null ? null : safe.getStartDate().atStartOfDay(),
                safe.getEndDate() == null ? null : safe.getEndDate().plusDays(1).atStartOfDay(),
                keyword,
                nonNegative(safe.getMinCommentCount()),
                nonNegative(safe.getMinViewCount()),
                nonNegative(safe.getMinLikeCount()),
                orderColumnOf(safe.getSortBy()),
                "asc".equalsIgnoreCase(safe.getSortOrder())));
        List<Moment> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(page.getCurrent(), page.getSize());
        }
        return PageResult.of(render(rows, userId), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 内容形态只认 1/2/3，其余（包括手改出来的脏值）当没筛。 */
    private static Integer knownKind(Integer kind) {
        return kind != null
                && (kind == MomentMapper.KIND_IMAGE || kind == MomentMapper.KIND_VIDEO || kind == MomentMapper.KIND_TEXT)
                ? kind : null;
    }

    /** 状态只认 1/2；不传就是不限，包括已下架的那几条。 */
    private static Integer knownStatus(Integer status) {
        return status != null && (status == Moment.STATUS_NORMAL || status == Moment.STATUS_OFFLINE) ? status : null;
    }

    /** 下限为负数等同于 0：一个负的阈值筛不出任何东西，不如归成「不筛」。 */
    private static Integer nonNegative(Integer value) {
        return value == null ? null : Math.max(value, 0);
    }

    /**
     * 排序字典：<b>字典在服务层，列名在方法引用里</b>。
     *
     * <p>按评论数 / 浏览量 / 点赞数排时给的是这些列，它们包不到索引（一个用户的动态最多几百条，
     * {@code idx_user_status_time} 先把自己那批行圈出来，剩下的 filesort 很便宜），
     * 所以不值得为它们多建三个索引。
     */
    private static SFunction<Moment, ?> orderColumnOf(String sortBy) {
        if (sortBy == null) {
            return Moment::getCreateTime;
        }
        return switch (sortBy.trim().toLowerCase()) {
            case "comment" -> Moment::getCommentCount;
            case "view" -> Moment::getViewCount;
            case "like" -> Moment::getLikeCount;
            default -> Moment::getCreateTime;
        };
    }

    @Override
    public MomentVO detail(Long viewerId, Long momentId) {
        Moment moment = momentMapper.selectById(momentId);
        BusinessException.throwIf(moment == null, ResultCode.MOMENT_GONE);
        boolean mine = viewerId.equals(moment.getUserId());
        if (!mine) {
            // 已下架的行对作者本人仍然可见（他要看到「我这条被下架了」），对外一律是「不在了」
            BusinessException.throwUnless(moment.isNormal(), ResultCode.MOMENT_GONE);
            // filterVisible 不判 status：那一条由上面的 SQL 或这句显式检查负责，两处都判只会互相掩盖
            boolean visible = !filterVisible(List.of(moment), viewerOf(viewerId), LocalDateTime.now()).isEmpty();
            BusinessException.throwUnless(visible, ResultCode.MOMENT_GONE);
        }
        MomentVO vo = render(List.of(moment), viewerId).get(0);
        vo.setComments(buildComments(momentCommentMapper.selectByMomentId(momentId), viewerId));
        if (!mine) {
            // 浏览量只数「别人点开」：作者自己反复看自己那一条不算热度，否则刚发的动态
            // 会在自己的「按浏览量」排序里永远排在最前。不判重复访客（口径同 im_article），
            // 也不参与任何业务判定，所以失败就失败，不为它多开一个事务。
            momentMapper.increaseViewCount(momentId);
        }
        return vo;
    }

    @Override
    public MomentCardDTO cardOf(Long momentId, Long viewerId) {
        Moment moment = momentMapper.selectNormalById(momentId);
        if (moment == null || viewerId == null) {
            return null;
        }
        if (!viewerId.equals(moment.getUserId())
                && filterVisible(List.of(moment), viewerOf(viewerId), LocalDateTime.now()).isEmpty()) {
            return null;
        }
        return MomentCardDTO.builder()
                .momentId(moment.getId())
                .authorId(moment.getUserId())
                .authorNickname(displayName(userQuerySpi.getById(moment.getUserId()), moment.getUserId()))
                .summary(TextUtil.summary(moment.getContent(), SUMMARY_MAX))
                .mediaCount(moment.getMediaCount())
                .build();
    }

    /* ==================== 写侧 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long momentId) {
        Moment moment = momentMapper.selectNormalById(momentId);
        // 「不是你的」与「不存在」回同一个码：动态 ID 的存在性不该被逐个试出来
        BusinessException.throwIf(moment == null || !userId.equals(moment.getUserId()), ResultCode.MOMENT_GONE);

        momentMapper.deleteById(momentId);
        momentMediaMapper.deleteByMomentId(momentId);
        momentCommentMapper.deleteByMomentId(momentId);
        // 点赞与白名单两张表没有 deleted 列，是物理删：留着只会成为指向空气的记录
        momentLikeMapper.deleteByMomentId(momentId);
        momentVisibleMapper.deleteByMomentId(momentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer like(Long userId, Long momentId) {
        Moment moment = requireVisible(userId, momentId);
        MomentLike row = new MomentLike();
        row.setMomentId(momentId);
        row.setUserId(userId);
        boolean inserted;
        try {
            momentLikeMapper.insert(row);
            inserted = true;
        } catch (DuplicateKeyException e) {
            // 重复点赞按幂等处理：前端连点两下不该让计数涨两次，也不该给用户报一个错
            inserted = false;
        }
        if (inserted) {
            momentMapper.adjustLikeCount(momentId, 1);
            addIntimacy(userId, moment.getUserId(), true);
        }
        return likeCountOf(momentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer unlike(Long userId, Long momentId) {
        requireVisible(userId, momentId);
        // 返回值是幂等性的唯一依据：0 行说明本来就没赞过，这时候再减一次就会把计数拖下去
        if (momentLikeMapper.deleteBy(momentId, userId) > 0) {
            momentMapper.adjustLikeCount(momentId, -1);
        }
        return likeCountOf(momentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MomentCommentVO comment(Long userId, Long momentId, MomentCommentRequest request) {
        Moment moment = requireVisible(userId, momentId);
        String content = sensitiveWordFilter.mask(TextUtil.sanitize(request.getContent()));
        BusinessException.throwIf(TextUtil.isBlank(content), ResultCode.BAD_REQUEST, "评论不能为空");
        BusinessException.throwIf(content.length() > MomentComment.CONTENT_MAX,
                ResultCode.BAD_REQUEST, "评论最多 " + MomentComment.CONTENT_MAX + " 字");

        long parentId = MomentComment.PARENT_ROOT;
        Long replyUserId = null;
        if (request.getParentId() != null && request.getParentId() != MomentComment.PARENT_ROOT) {
            MomentComment parent = momentCommentMapper.selectNormalById(request.getParentId());
            // 父评论不在这一条动态下面时同样回「不在了」：它属于哪条动态不该被外部试出来
            BusinessException.throwIf(parent == null || !momentId.equals(parent.getMomentId()),
                    ResultCode.MOMENT_COMMENT_GONE);
            BusinessException.throwUnless(parent.isRoot(), ResultCode.MOMENT_REPLY_TOO_DEEP);
            parentId = parent.getId();
            replyUserId = resolveReplyUser(parent, request.getReplyUserId());
        }

        MomentComment row = new MomentComment();
        row.setMomentId(momentId);
        row.setUserId(userId);
        row.setParentId(parentId);
        row.setReplyUserId(replyUserId);
        row.setContent(content.trim());
        row.setLikeCount(0);
        row.setStatus(MomentComment.STATUS_NORMAL);
        momentCommentMapper.insert(row);
        momentMapper.adjustCommentCount(momentId, 1);
        addIntimacy(userId, moment.getUserId(), false);

        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(collectCommentUsers(List.of(row)));
        return toCommentVO(row, users, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long userId, Long commentId) {
        MomentComment comment = momentCommentMapper.selectNormalById(commentId);
        BusinessException.throwIf(comment == null, ResultCode.MOMENT_COMMENT_GONE);
        Moment moment = momentMapper.selectById(comment.getMomentId());
        BusinessException.throwIf(moment == null, ResultCode.MOMENT_GONE);
        // 评论作者与动态作者都能删：后者是「我不想在我的动态下看到这句话」，这是内容主人的正当权力
        boolean allowed = userId.equals(comment.getUserId()) || userId.equals(moment.getUserId());
        BusinessException.throwUnless(allowed, ResultCode.MOMENT_COMMENT_GONE);

        int removed = momentCommentMapper.deleteWithChildren(commentId);
        if (removed > 0) {
            // 减的是整棵子树的行数：一级评论下面的回复也计在 comment_count 里，只减 1 会让数字永远虚高
            momentMapper.adjustCommentCount(comment.getMomentId(), -removed);
        }
    }

    @Override
    public MomentShareVO share(Long userId, Long momentId, MomentShareRequest request) {
        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        BusinessException.throwIf(messageSpi == null, ResultCode.SYSTEM_ERROR, "消息模块未装配");

        // 这里先判一次不是为了拦人（消息模块的 MomentContentHandler 会再判一次），
        // 而是为了让错误码出自交友侧的 2050 而不是消息侧的「内容不合法」：用户看到的是「这条动态不在了」
        requireVisible(userId, momentId);

        Long conversationId = request.getConversationId();
        Long toUserId = request.getToUserId();
        BusinessException.throwIf(conversationId == null && toUserId == null,
                ResultCode.BAD_REQUEST, "conversationId 与 toUserId 至少填写一个");
        BusinessException.throwIf(conversationId == null && toUserId.equals(userId),
                ResultCode.BAD_REQUEST, "不能分享给自己");

        MessageSendCmd cmd = MessageSendCmd.builder()
                .clientMsgId(TextUtil.isNotBlank(request.getClientMsgId())
                        ? request.getClientMsgId().trim() : "mom" + IdWorker.getId())
                .conversationId(conversationId)
                // 给了会话就不再看 toUserId：两个都传而指向不同的人时，以会话为准才不会发错地方
                .toUserId(conversationId == null ? toUserId : null)
                .fromUserId(userId)
                .msgType(MsgType.MOMENT.getCode())
                .content(request.getMessage())
                .extra(MessageExtra.builder().momentId(momentId).build())
                .build();
        MessageDTO sent = messageSpi.send(cmd);
        return MomentShareVO.builder()
                .conversationId(sent.getConversationId())
                .messageId(sent.getMessageId())
                .build();
    }

    /* ==================== 可见性 ==================== */

    /**
     * 一次请求内的关系快照。
     *
     * <p>做成一个不可变的记录而不是每次用到就查一遍：Feed 补捞三轮时这三张表只该读一次，
     * 否则同一屏里「好友」这个概念会有三个不同的取值。
     */
    private record Viewer(Long id, Set<Long> friends, Set<Long> matched, Set<Long> blocked) {
    }

    private Viewer viewerOf(Long userId) {
        Set<Long> friends = new HashSet<>();
        for (Friend relation : friendMapper.selectNormalByUserId(userId)) {
            friends.add(relation.getFriendId());
        }
        Set<Long> matched = new HashSet<>();
        for (MatchPair pair : matchPairMapper.selectActiveByUser(userId)) {
            Long peer = pair.peerId(userId);
            if (peer != null) {
                matched.add(peer);
            }
        }
        return new Viewer(userId, friends, matched, new HashSet<>(friendMapper.selectBlockedFriendIds(userId)));
    }

    /** 受众 = 好友 ∪ 配对中 ∪ 自己 − 我拉黑的人；上限与挤掉谁见 {@link #AUDIENCE_MAX}。 */
    private Set<Long> audienceOf(Viewer viewer) {
        Set<Long> audience = new HashSet<>(viewer.friends());
        audience.addAll(viewer.matched());
        audience.removeAll(viewer.blocked());
        // 自己最后加：拉黑名单里不会有自己，但这一句保证「我的动态一定在我的 Feed 里」不受上面几步影响
        audience.add(viewer.id());
        if (audience.size() <= AUDIENCE_MAX) {
            return audience;
        }
        return audience.stream().sorted((a, b) -> Long.compare(b, a)).limit(AUDIENCE_MAX)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 逐条过可见范围，返回看得到的那些（保持入参顺序）。
     *
     * <p>白名单与「几天可见」都是<b>一次批量查完</b>：一屏十几条动态逐条查就是几十次往返。
     */
    private List<Moment> filterVisible(List<Moment> candidates, Viewer viewer, LocalDateTime now) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> momentIds = candidates.stream().map(Moment::getId).toList();
        Set<Long> whitelisted = new HashSet<>(momentVisibleMapper.selectVisibleMomentIds(momentIds, viewer.id()));

        Set<Long> authorIds = new HashSet<>();
        for (Moment moment : candidates) {
            if (!viewer.id().equals(moment.getUserId())) {
                authorIds.add(moment.getUserId());
            }
        }
        // 没有资料行的人不在结果里，MomentVisibleDays#of(null) 会退到最严的「最近三天」
        Map<Long, Integer> visibleDays = authorIds.isEmpty() ? Map.of()
                : datingQuerySpi.momentVisibleDays(authorIds);

        List<Moment> result = new ArrayList<>(candidates.size());
        for (Moment moment : candidates) {
            Long authorId = moment.getUserId();
            if (viewer.id().equals(authorId)) {
                result.add(moment);
                continue;
            }
            if (viewer.blocked().contains(authorId)) {
                continue;
            }
            LocalDateTime cutoff = MomentVisibleDays.of(visibleDays.get(authorId)).cutoff(now);
            if (cutoff != null && (moment.getCreateTime() == null || moment.getCreateTime().isBefore(cutoff))) {
                continue;
            }
            if (moment.visibleTo(viewer.id(), viewer.friends().contains(authorId),
                    viewer.matched().contains(authorId), whitelisted.contains(moment.getId()))) {
                result.add(moment);
            }
        }
        return result;
    }

    /** 「看得到才动得了」：点赞、评论与转发都先过这一关。 */
    private Moment requireVisible(Long userId, Long momentId) {
        Moment moment = momentMapper.selectNormalById(momentId);
        BusinessException.throwIf(moment == null, ResultCode.MOMENT_GONE);
        if (!userId.equals(moment.getUserId())) {
            boolean visible = !filterVisible(List.of(moment), viewerOf(userId), LocalDateTime.now()).isEmpty();
            BusinessException.throwUnless(visible, ResultCode.MOMENT_GONE);
        }
        return moment;
    }

    /* ==================== 组装 ==================== */

    /**
     * 批量渲染：作者、附件、我赞过没有，三样各一次查完。
     *
     * <p><b>不带评论</b>：MySQL 里「每条动态各取前 N 条」要么用窗口函数（版本风险 + 难读），
     * 要么逐条查（N 次往返），要么 {@code LIMIT n*N} 让一条被刷屏的动态挤掉别人的名额——
     * 那是错误内容而不只是少内容。所以 Feed 只下发 {@code commentCount}，评论进详情页看。
     */
    private List<MomentVO> render(List<Moment> moments, Long viewerId) {
        if (moments.isEmpty()) {
            return List.of();
        }
        List<Long> momentIds = moments.stream().map(Moment::getId).toList();
        Map<Long, List<MomentMedia>> mediaByMoment = groupMedia(momentMediaMapper.selectByMomentIds(momentIds));
        Set<Long> liked = new HashSet<>(momentLikeMapper.selectLikedMomentIds(viewerId, momentIds));
        Set<Long> authorIds = new HashSet<>();
        moments.forEach(moment -> authorIds.add(moment.getUserId()));
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(authorIds);

        List<MomentVO> result = new ArrayList<>(moments.size());
        for (Moment moment : moments) {
            boolean mine = viewerId.equals(moment.getUserId());
            List<MomentMedia> media = mediaByMoment.getOrDefault(moment.getId(), List.of());
            MomentVO vo = MomentVO.builder()
                    .momentId(moment.getId())
                    .author(users.get(moment.getUserId()))
                    .content(moment.getContent())
                    .mediaCount(moment.getMediaCount())
                    .media(media.stream().map(this::toMediaVO).toList())
                    .likeCount(moment.getLikeCount())
                    .commentCount(moment.getCommentCount())
                    .liked(liked.contains(moment.getId()))
                    .createTime(moment.getCreateTime())
                    .mine(mine)
                    .build();
            if (mine) {
                // 可见范围与状态只对作者本人下发：别人看到「仅好友」这一标，
                // 等于从侧面知道了「我是这个人的好友」（他能看到的可能是公开也可能是仅好友），
                // 而这一信息对渲染没有任何用处。浏览量同一个口径：它是作者自己的运营数据。
                vo.setVisibility(moment.getVisibility());
                vo.setVisibilityDesc(MomentVisibility.descOf(moment.getVisibility()));
                vo.setStatus(moment.getStatus());
                vo.setStatusDesc(statusDesc(moment.getStatus()));
                vo.setViewCount(moment.getViewCount() == null ? 0 : moment.getViewCount());
            }
            result.add(vo);
        }
        return result;
    }

    /** 按 {@code moment_id} 分组保序：行已经排好，用 {@code LinkedHashMap} 才不会打乱九宫格顺序。 */
    private Map<Long, List<MomentMedia>> groupMedia(List<MomentMedia> rows) {
        Map<Long, List<MomentMedia>> grouped = new LinkedHashMap<>();
        for (MomentMedia row : rows) {
            grouped.computeIfAbsent(row.getMomentId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * 附件 VO。
     *
     * <p><b>不下发 {@code fileId}</b>：文件下载的鉴权依据是「文件所在会话的成员」，
     * 而动态附件不在任何会话里，拿到 ID 也只会得到一个 403；地址已经在 {@code url} 上了。
     */
    private MomentMediaVO toMediaVO(MomentMedia media) {
        return MomentMediaVO.builder()
                .mediaId(media.getId())
                .url(media.getUrl())
                .type(media.getType())
                .sort(media.getSort())
                .width(media.getWidth())
                .height(media.getHeight())
                .duration(media.getDuration())
                .build();
    }

    /** 平铺的评论行拼成两层：一级评论在外，回复挂在 {@code replies} 里。 */
    private List<MomentCommentVO> buildComments(List<MomentComment> rows, Long viewerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(collectCommentUsers(rows));
        Map<Long, MomentCommentVO> roots = new LinkedHashMap<>();
        for (MomentComment row : rows) {
            if (row.isRoot()) {
                roots.put(row.getId(), toCommentVO(row, users, viewerId));
            }
        }
        for (MomentComment row : rows) {
            if (row.isRoot()) {
                continue;
            }
            MomentCommentVO parent = roots.get(row.getParentId());
            // 父评论被删掉时这条回复挂不上去，直接丢掉：一条没有上下文的「回复某人」读不出意思，
            // 而 selectByMomentId 只取正常行，被删的父行本来就不会出现在 roots 里
            if (parent == null) {
                continue;
            }
            if (parent.getReplies() == null) {
                parent.setReplies(new ArrayList<>());
            }
            parent.getReplies().add(toCommentVO(row, users, viewerId));
        }
        return new ArrayList<>(roots.values());
    }

    private Set<Long> collectCommentUsers(Collection<MomentComment> rows) {
        Set<Long> userIds = new HashSet<>();
        for (MomentComment row : rows) {
            userIds.add(row.getUserId());
            if (row.getReplyUserId() != null) {
                userIds.add(row.getReplyUserId());
            }
        }
        return userIds;
    }

    private MomentCommentVO toCommentVO(MomentComment row, Map<Long, UserBriefDTO> users, Long viewerId) {
        return MomentCommentVO.builder()
                .commentId(row.getId())
                .momentId(row.getMomentId())
                .user(users.get(row.getUserId()))
                .parentId(row.isRoot() ? MomentComment.PARENT_ROOT : row.getParentId())
                .replyUser(row.getReplyUserId() == null ? null : users.get(row.getReplyUserId()))
                .content(row.getContent())
                .createTime(row.getCreateTime())
                .mine(viewerId.equals(row.getUserId()))
                .build();
    }

    /**
     * 复核「被回复人」。
     *
     * <p>只有当这个 ID 出现在<b>父评论的作者</b>或<b>同一棵子树里已经说过话的人</b>当中时才采信，
     * 否则退回父评论的作者。不这么做的话，「A 回复 B」里的 B 可以是一个从没在这条动态下出现过的人，
     * 而前端会照着这个快照把 B 的头像与昵称渲染进评论区——那就是把一个人的身份挂到一段他没参与的对话上。
     */
    private Long resolveReplyUser(MomentComment parent, Long requested) {
        if (requested == null) {
            return parent.getUserId();
        }
        if (requested.equals(parent.getUserId())) {
            return requested;
        }
        for (MomentComment sibling : momentCommentMapper.selectChildren(parent.getId())) {
            if (requested.equals(sibling.getUserId())) {
                return requested;
            }
        }
        return parent.getUserId();
    }

    /* ==================== 杂项 ==================== */

    private int normalizeSize(Integer size) {
        return size == null ? FEED_DEFAULT_SIZE : Math.max(1, Math.min(size, FEED_MAX_SIZE));
    }

    private Integer likeCountOf(Long momentId) {
        Moment fresh = momentMapper.selectById(momentId);
        return fresh == null || fresh.getLikeCount() == null ? 0 : fresh.getLikeCount();
    }

    private String statusDesc(Integer status) {
        return status != null && status == Moment.STATUS_OFFLINE ? "已下架" : "正常";
    }

    /** 昵称兜底：账号被注销时 {@code user} 为 null，卡片上仍需有个能读的名字。 */
    private String displayName(UserBriefDTO user, Long userId) {
        if (user == null) {
            return "用户" + userId;
        }
        if (TextUtil.isNotBlank(user.getNickname())) {
            return user.getNickname();
        }
        return TextUtil.isNotBlank(user.getUsername()) ? user.getUsername() : "用户" + userId;
    }

    private FileStorageSpi requireStorage() {
        FileStorageSpi storage = fileStorageSpiProvider.getIfAvailable();
        BusinessException.throwIf(storage == null, ResultCode.FILE_STORAGE_UNAVAILABLE);
        return storage;
    }

    /**
     * 加亲密度。
     *
     * <p>自己给自己点赞 / 评论时不调：亲密度问的是「两个人的关系」，
     * 而 {@code im_intimacy} 的主键约定 {@code user_a_id < user_b_id}，同一个人填进去就是一行自环。
     */
    private void addIntimacy(Long operatorId, Long authorId, boolean like) {
        if (operatorId.equals(authorId)) {
            return;
        }
        IntimacySpi intimacySpi = intimacySpiProvider.getIfAvailable();
        if (intimacySpi == null) {
            return;
        }
        afterCommit(() -> {
            if (like) {
                intimacySpi.onMomentLike(operatorId, authorId);
            } else {
                intimacySpi.onMomentComment(operatorId, authorId);
            }
        });
    }

    /** 事务提交之后再做的不可回滚动作；没有事务时（单元测试直接调）就地执行。 */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
