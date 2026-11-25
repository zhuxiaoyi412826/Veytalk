package com.im.friend.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminCommentQueryDTO;
import com.im.common.domain.AdminMomentCommentDTO;
import com.im.common.domain.AdminMomentDTO;
import com.im.common.domain.AdminMomentQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminMomentSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.friend.entity.Moment;
import com.im.friend.entity.MomentComment;
import com.im.friend.entity.MomentMedia;
import com.im.friend.mapper.MomentCommentMapper;
import com.im.friend.mapper.MomentLikeMapper;
import com.im.friend.mapper.MomentMapper;
import com.im.friend.mapper.MomentMediaMapper;
import com.im.friend.mapper.MomentVisibleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 后台动态管理契约的实现（{@link AdminMomentSpi}）。
 *
 * <p>类名带模块前缀的理由见 {@code AdminMetricsSpi} 接口注释。
 *
 * <p>为什么不走用户端 {@code MomentService} 的方法：那里每个读都带受众视角
 * （可见范围、白名单、三天可见、是否本人），后台要的恰恰是「无视角的全状态行」——
 * 已下架的、仅自己可见的那些，在用户端服务里根本查不出来。所以本类直接对 mapper 说话，
 * 只在「删一条动态要清哪几张附属表」这一处与 {@code MomentServiceImpl#delete} 严格对齐。
 *
 * <p>计数回写只发生在删除上：{@code im_moment.comment_count} 的含义是「可见的评论行数」，
 * 因此下架一条评论时按本次真正改动的行数减、恢复时加回，而不是拍一个 1——
 * 一级评论带着回复时，减 1 会让这个数字从此永远虚高一条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MomentAdminSpiImpl implements AdminMomentSpi {

    /** 单页上限，与其余后台契约同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 关键词长度上限，防止一条超长 like 拖死 im_moment 的全表扫 */
    private static final int KEYWORD_MAX = 50;
    /**
     * 列表上每条动态最多带几个附件地址。
     *
     * <p>只为认图，不为看完：九宫格全量在详情里，而一屏 20 条动态若各带 9 张图就是 180 个
     * 图片请求，后台的翻页体验会直接崩掉。
     */
    private static final int MEDIA_PREVIEW = 3;
    /** 评论列表里那条动态的摘要长度 */
    private static final int MOMENT_SUMMARY = 60;

    private final MomentMapper momentMapper;
    private final MomentMediaMapper momentMediaMapper;
    private final MomentCommentMapper momentCommentMapper;
    private final MomentLikeMapper momentLikeMapper;
    private final MomentVisibleMapper momentVisibleMapper;
    private final UserQuerySpi userQuerySpi;

    /* ==================== 动态 ==================== */

    @Override
    public PageResult<AdminMomentDTO> pageMoments(AdminMomentQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = clampSize(query.getSize());
        LambdaQueryWrapper<Moment> wrapper = new LambdaQueryWrapper<Moment>()
                .eq(query.getUserId() != null, Moment::getUserId, query.getUserId())
                .eq(query.getStatus() != null, Moment::getStatus, query.getStatus())
                .eq(query.getVisibility() != null, Moment::getVisibility, query.getVisibility());
        applyMomentKeyword(wrapper, query.getKeyword());
        // 按 ID 倒序：雪花 ID 单调递增，等价于发布时间倒序，且翻页顺序稳定
        wrapper.orderByDesc(Moment::getId);
        Page<Moment> result = momentMapper.selectPage(new Page<>(current, size), wrapper);
        List<Moment> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> authorIds = new LinkedHashSet<>();
        Set<Long> momentIds = new LinkedHashSet<>();
        for (Moment row : rows) {
            authorIds.add(row.getUserId());
            momentIds.add(row.getId());
        }
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(authorIds);
        Map<Long, List<MomentMedia>> preview = mediaPreview(momentIds);
        List<AdminMomentDTO> dtos = new ArrayList<>(rows.size());
        for (Moment row : rows) {
            dtos.add(toMomentDTO(row, users.get(row.getUserId()),
                    preview.getOrDefault(row.getId(), List.of())));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminMomentDTO detail(Long momentId) {
        Moment moment = requireMoment(momentId);
        // 详情给全量附件：后台点开的就是这一条，截断只会逼人再去调一次用户端接口（而它按受众过滤）
        return toMomentDTO(moment, briefOf(moment.getUserId()), mediaRows(momentId, 0));
    }

    @Override
    public AdminMomentDTO setStatus(Long momentId, boolean online, Long operatorId, String reason) {
        Moment moment = requireMoment(momentId);
        int target = online ? Moment.STATUS_NORMAL : Moment.STATUS_OFFLINE;
        if (!Objects.equals(moment.getStatus(), target)) {
            // 带状态条件更新，兼作乐观锁：作者在同一刻自己删了这条时，这里改到 0 行而不是把已删的行拉回来
            int changed = momentMapper.updateStatusIf(momentId, moment.getStatus(), target);
            if (changed > 0) {
                moment.setStatus(target);
            } else {
                Moment latest = momentMapper.selectById(momentId);
                BusinessException.throwIf(latest == null, ResultCode.MOMENT_GONE, "动态已被删除");
                moment = latest;
            }
        }
        log.info("[后台动态管理] operator={} 将动态 {} 置为{}: userId={}, reason={}",
                operatorId, momentId, online ? "上架" : "下架", moment.getUserId(), trimToNull(reason));
        return toMomentDTO(moment, briefOf(moment.getUserId()), mediaRows(momentId, MEDIA_PREVIEW));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long momentId, Long operatorId) {
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null) {
            // 幂等：作者本人已删过的行再删一次不报错
            return;
        }
        // 五张表的落点与用户端本人删除逐字一致：主行与媒体、评论是逻辑删，点赞与白名单是物理删
        momentMapper.deleteById(momentId);
        momentMediaMapper.deleteByMomentId(momentId);
        momentCommentMapper.deleteByMomentId(momentId);
        momentLikeMapper.deleteByMomentId(momentId);
        momentVisibleMapper.deleteByMomentId(momentId);
        log.info("[后台动态管理] operator={} 删除动态 {}: userId={}, likeCount={}, commentCount={}",
                operatorId, momentId, moment.getUserId(), moment.getLikeCount(), moment.getCommentCount());
    }

    /* ==================== 评论 ==================== */

    @Override
    public PageResult<AdminMomentCommentDTO> pageComments(AdminCommentQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = clampSize(query.getSize());
        String keyword = clampKeyword(query.getKeyword());
        Long commentId = keyword != null && !keyword.isEmpty() && keyword.chars().allMatch(Character::isDigit)
                ? Long.parseLong(keyword) : null;
        LambdaQueryWrapper<MomentComment> wrapper = new LambdaQueryWrapper<MomentComment>()
                .eq(query.getMomentId() != null, MomentComment::getMomentId, query.getMomentId())
                .eq(query.getUserId() != null, MomentComment::getUserId, query.getUserId())
                .eq(query.getStatus() != null, MomentComment::getStatus, query.getStatus())
                .like(TextUtil.isNotBlank(keyword) && commentId == null, MomentComment::getContent, keyword)
                .eq(commentId != null, MomentComment::getId, commentId)
                .orderByDesc(MomentComment::getId);
        Page<MomentComment> result = momentCommentMapper.selectPage(new Page<>(current, size), wrapper);
        List<MomentComment> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> momentIds = new LinkedHashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (MomentComment row : rows) {
            momentIds.add(row.getMomentId());
            userIds.add(row.getUserId());
            if (row.getReplyUserId() != null && row.getReplyUserId() != 0L) {
                userIds.add(row.getReplyUserId());
            }
        }
        // 用 selectBatchIds 而不是 selectNormalByIds：后台要能看到「已下架那条动态下的评论」
        Map<Long, Moment> moments = momentMap(momentIds);
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(userIds);
        List<AdminMomentCommentDTO> dtos = new ArrayList<>(rows.size());
        for (MomentComment row : rows) {
            dtos.add(toCommentDTO(row, moments.get(row.getMomentId()), users));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminMomentCommentDTO setCommentStatus(Long commentId, boolean online, Long operatorId, String reason) {
        MomentComment comment = requireComment(commentId);
        int target = online ? MomentComment.STATUS_NORMAL : MomentComment.STATUS_OFFLINE;
        if (!Objects.equals(comment.getStatus(), target)) {
            int changed = momentCommentMapper.updateStatusIf(commentId, comment.getStatus(), target);
            // 0 行说明同一刻被并发改走或删掉了：不报错，按当前行回给前端，让那一屏自己刷新
            if (changed > 0) {
                comment.setStatus(target);
                if (online) {
                    momentMapper.adjustCommentCount(comment.getMomentId(), 1);
                } else {
                    // 下架一级评论时连带藏掉它当前的回复，减的是「本次真正改动的行数」
                    momentMapper.adjustCommentCount(comment.getMomentId(), -hideSubtree(commentId));
                }
            } else {
                MomentComment latest = momentCommentMapper.selectById(commentId);
                BusinessException.throwIf(latest == null, ResultCode.MOMENT_COMMENT_GONE, "评论已被删除");
                comment = latest;
            }
        }
        log.info("[后台动态管理] operator={} 将评论 {} 置为{}: momentId={}, reason={}",
                operatorId, commentId, online ? "正常" : "下架", comment.getMomentId(), trimToNull(reason));
        return toCommentDTO(comment, momentMapper.selectById(comment.getMomentId()),
                userQuerySpi.listByIds(commentUsers(comment)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long operatorId) {
        MomentComment comment = momentCommentMapper.selectById(commentId);
        if (comment == null) {
            return;
        }
        int removed = momentCommentMapper.deleteWithChildren(commentId);
        if (removed > 0) {
            // 减整棵子树，理由与用户端本人删除相同：回复也计在 comment_count 里
            momentMapper.adjustCommentCount(comment.getMomentId(), -removed);
        }
        log.info("[后台动态管理] operator={} 删除评论 {}: momentId={}, 连带子树 {} 行",
                operatorId, commentId, comment.getMomentId(), removed);
    }

    /* ==================== 查询与组装 ==================== */

    /**
     * 关键词口径：正文模糊匹配；纯数字时额外认动态 ID 与作者 ID。
     *
     * <p>三个条件是<b>或</b>关系并整体加括号：不括号的话 {@code status} 那类 eq 条件会被
     * {@code OR} 穿透，筛「已下架」的列表里就混进正常行。
     */
    private void applyMomentKeyword(LambdaQueryWrapper<Moment> wrapper, String rawKeyword) {
        String keyword = clampKeyword(rawKeyword);
        if (keyword == null) {
            return;
        }
        boolean numeric = !keyword.isEmpty() && keyword.chars().allMatch(Character::isDigit);
        wrapper.and(w -> {
            w.like(Moment::getContent, keyword);
            if (numeric) {
                long value = Long.parseLong(keyword);
                w.or().eq(Moment::getId, value).or().eq(Moment::getUserId, value);
            }
        });
    }

    private Moment requireMoment(Long momentId) {
        BusinessException.throwIf(momentId == null, ResultCode.BAD_REQUEST, "动态 ID 不能为空");
        Moment moment = momentMapper.selectById(momentId);
        BusinessException.throwIf(moment == null, ResultCode.NOT_FOUND, "动态不存在或已被删除");
        return moment;
    }

    private MomentComment requireComment(Long commentId) {
        BusinessException.throwIf(commentId == null, ResultCode.BAD_REQUEST, "评论 ID 不能为空");
        MomentComment comment = momentCommentMapper.selectById(commentId);
        BusinessException.throwIf(comment == null, ResultCode.NOT_FOUND, "评论不存在或已被删除");
        return comment;
    }

    /**
     * 下架一棵子树（一级评论 + 它当前状态正常的回复），返回真正被改动几行。
     *
     * <p>只藏「状态正常」的子行：本来就被单独下架过的回复不该被这次操作改回正常态，
     * 那等于把上一次处置的痕迹抹掉，而 {@code comment_count} 也会因此多加一行。
     */
    private int hideSubtree(Long commentId) {
        List<MomentComment> children = momentCommentMapper.selectChildren(commentId);
        int hidden = 1;
        for (MomentComment child : children) {
            if (Objects.equals(child.getStatus(), MomentComment.STATUS_NORMAL)) {
                momentCommentMapper.updateStatusIf(child.getId(), MomentComment.STATUS_NORMAL,
                        MomentComment.STATUS_OFFLINE);
                hidden++;
            }
        }
        return hidden;
    }

    /**
     * 一批动态的附件预览，按发布时的顺序每人最多 {@link #MEDIA_PREVIEW} 个。
     *
     * <p>回的是整行而不是地址串：后台需要知道每一个附件是图还是视频，而这个信息只长在
     * {@code im_moment_media.type} 上；先抽成 {@code List<String>} 再回头补类型，就只能多查一次。
     */
    private Map<Long, List<MomentMedia>> mediaPreview(Collection<Long> momentIds) {
        return mediaGrouped(momentIds, MEDIA_PREVIEW);
    }

    /** 单条动态的附件行；{@code limit} 传 0 表示不限量。 */
    private List<MomentMedia> mediaRows(Long momentId, int limit) {
        List<MomentMedia> all = new ArrayList<>();
        for (MomentMedia media : momentMediaMapper.selectByMomentId(momentId)) {
            if (limit > 0 && all.size() >= limit) {
                break;
            }
            all.add(media);
        }
        return all;
    }

    private Map<Long, List<MomentMedia>> mediaGrouped(Collection<Long> momentIds, int limit) {
        if (momentIds == null || momentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MomentMedia>> grouped = new LinkedHashMap<>();
        // 行已由 Mapper 按 (moment_id, sort, id) 排好，这里只分组截断，不再排一次
        for (MomentMedia media : momentMediaMapper.selectByMomentIds(momentIds)) {
            List<MomentMedia> bucket = grouped.computeIfAbsent(media.getMomentId(), k -> new ArrayList<>());
            if (bucket.size() < limit) {
                bucket.add(media);
            }
        }
        return grouped;
    }

    private Map<Long, Moment> momentMap(Collection<Long> momentIds) {
        Map<Long, Moment> result = new HashMap<>(momentIds.size());
        for (Moment moment : momentMapper.selectByIds(new ArrayList<>(momentIds))) {
            result.put(moment.getId(), moment);
        }
        return result;
    }

    private UserBriefDTO briefOf(Long userId) {
        return userId == null ? null : userQuerySpi.getById(userId);
    }

    private static Set<Long> commentUsers(MomentComment comment) {
        Set<Long> ids = new HashSet<>();
        ids.add(comment.getUserId());
        if (comment.getReplyUserId() != null && comment.getReplyUserId() != 0L) {
            ids.add(comment.getReplyUserId());
        }
        return ids;
    }

    /**
     * 拼后台用的一行动态。附件行在这里才拆成「地址 + 类型」两个同序列：
     * 只在拼 DTO 的这一处拆，两个列表就不可能因为中途多一次排序而错位。
     */
    private AdminMomentDTO toMomentDTO(Moment moment, UserBriefDTO author, List<MomentMedia> media) {
        List<String> urls = new ArrayList<>(media.size());
        List<Integer> types = new ArrayList<>(media.size());
        for (MomentMedia row : media) {
            urls.add(row.getUrl());
            types.add(row.getType());
        }
        return AdminMomentDTO.builder()
                .id(moment.getId())
                .userId(moment.getUserId())
                .userNickname(displayName(author))
                .content(moment.getContent())
                .visibility(moment.getVisibility())
                .mediaCount(moment.getMediaCount())
                .mediaUrls(urls)
                .mediaTypes(types)
                .likeCount(moment.getLikeCount())
                .commentCount(moment.getCommentCount())
                .status(moment.getStatus())
                .createTime(moment.getCreateTime())
                .build();
    }

    private AdminMomentCommentDTO toCommentDTO(MomentComment comment, Moment moment, Map<Long, UserBriefDTO> users) {
        return AdminMomentCommentDTO.builder()
                .id(comment.getId())
                .momentId(comment.getMomentId())
                .momentSummary(moment == null ? "" : TextUtil.summary(moment.getContent(), MOMENT_SUMMARY))
                .userId(comment.getUserId())
                .userNickname(displayName(users.get(comment.getUserId())))
                .parentId(comment.getParentId())
                .replyUserId(comment.getReplyUserId())
                .replyUserNickname(hasReplyTarget(comment) ? displayName(users.get(comment.getReplyUserId())) : null)
                .content(comment.getContent())
                .likeCount(comment.getLikeCount())
                .status(comment.getStatus())
                .createTime(comment.getCreateTime())
                .build();
    }

    private static boolean hasReplyTarget(MomentComment comment) {
        return comment.getReplyUserId() != null && comment.getReplyUserId() != 0L;
    }

    /** 用户查不到（已注销）时留 ID：后台靠 ID 还能追，靠一个空串什么都追不到。 */
    private static String displayName(UserBriefDTO brief) {
        if (brief == null) {
            return "已注销用户";
        }
        return TextUtil.isNotBlank(brief.getNickname()) ? brief.getNickname() : brief.getUsername();
    }

    private static long clampSize(long size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private static String clampKeyword(String keyword) {
        if (TextUtil.isBlank(keyword)) {
            return null;
        }
        String text = keyword.trim();
        return text.length() > KEYWORD_MAX ? text.substring(0, KEYWORD_MAX) : text;
    }

    private static String trimToNull(String value) {
        return TextUtil.isBlank(value) ? null : value.trim();
    }
}
