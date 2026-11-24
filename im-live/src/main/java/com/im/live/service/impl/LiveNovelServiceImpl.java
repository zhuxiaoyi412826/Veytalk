package com.im.live.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.ResultCode;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.WorkBoardType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.live.dto.req.LiveNovelCreateRequest;
import com.im.live.dto.vo.WorkVO;
import com.im.live.entity.LiveNovel;
import com.im.live.mapper.LiveNovelMapper;
import com.im.live.service.LiveNovelService;
import com.im.live.service.WorkAccessUrls;
import com.im.live.service.WorkRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小说板块服务实现（Markdown 信息流）。
 *
 * <p>本类最重要的一行在下面 {@link #page} 里的 {@code select(...)}：<b>列表查询必须排除
 * {@code content}（LONGTEXT）</b>。MyBatis-Plus 默认 {@code select * }，一屏 15 篇长文就能把一次
 * 翻页变成几百 MB，还会把 {@code idx_status_create} 上的查询拖成大量回表读大字段。
 * 用「按列名过滤」而不是「显式列出要取的列」，是因为日后加列时后者会静默漏字段。
 *
 * <p>正文的取与写只有 {@link #detail(Long)} 与 {@link #publish}/{@link #edit} 四条路径，
 * 字数（{@code word_count}）与字节数（{@code size}）一律服务端现算，摘要留空则由正文截取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveNovelServiceImpl implements LiveNovelService {

    /** VO 上回填的板块标识 */
    private static final String BOARD = WorkBoardType.NOVEL.getCode();
    /** 占位标题里的板块中文名 */
    private static final String BOARD_NAME = "小说";

    private final LiveNovelMapper novelMapper;
    private final UserQuerySpi userQuerySpi;
    private final WorkAccessUrls accessUrls;

    @Override
    public Page<WorkVO> page(long current, long size, String category, String keyword, String sort, boolean mine) {
        Long me = SecurityUtil.getUserIdOrNull();
        Page<LiveNovel> page = new Page<>(current, size);
        LambdaQueryWrapper<LiveNovel> wrapper = new LambdaQueryWrapper<LiveNovel>()
                // 显式排除 LONGTEXT 正文：见类注释。其余列（含 summary/word_count）都是卡片要用的
                .select(LiveNovel.class, (TableFieldInfo field) -> !"content".equals(field.getColumn()))
                .eq(LiveNovel::getStatus, LiveNovel.STATUS_NORMAL);
        if (TextUtil.isNotBlank(category)) {
            wrapper.eq(LiveNovel::getCategory, category.trim());
        }
        // 搜索只搜标题：<b>不搜正文</b>——LONGTEXT 上的 LIKE 没有索引可用，一列长文全表扫会把整块拖死
        if (TextUtil.isNotBlank(keyword)) {
            wrapper.like(LiveNovel::getTitle, keyword.trim());
        }
        if (mine) {
            if (me == null) {
                Page<WorkVO> empty = new Page<>(current, size, 0);
                empty.setRecords(List.of());
                return empty;
            }
            wrapper.eq(LiveNovel::getUploaderId, me);
        }
        // 排序：hot 按阅读次数倒序（同量再按时间兜底），其余一律按发布时间倒序
        if ("hot".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(LiveNovel::getPlayCount).orderByDesc(LiveNovel::getCreateTime);
        } else {
            wrapper.orderByDesc(LiveNovel::getCreateTime);
        }
        Page<LiveNovel> result = novelMapper.selectPage(page, wrapper);

        Page<WorkVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<LiveNovel> records = result.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Set<Long> uploaderIds = new HashSet<>();
        for (LiveNovel novel : records) {
            uploaderIds.add(novel.getUploaderId());
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(uploaderIds);
        List<WorkVO> vos = new ArrayList<>(records.size());
        for (LiveNovel novel : records) {
            // 第二个参数 false：列表不带正文，这正是本方法存在的首要理由
            vos.add(toVO(novel, briefs.get(novel.getUploaderId()), me, false));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public WorkVO detail(Long id) {
        LiveNovel novel = novelMapper.selectById(id);
        // 已下架与不存在回同一个错：详情接口是前台能直接构造 URL 打到的，不能把「存在但被下架」漏出去
        if (novel == null || !Integer.valueOf(LiveNovel.STATUS_NORMAL).equals(novel.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作品不存在或已被删除");
        }
        increasePlayCount(id);
        // 计数是另一次 UPDATE，内存里的值仍是打开前的；展示时直接补 1，不为一个数字再读一遍库
        int played = novel.getPlayCount() == null ? 1 : novel.getPlayCount() + 1;
        WorkVO vo = toVO(novel, userQuerySpi.getById(novel.getUploaderId()), SecurityUtil.getUserIdOrNull(), true);
        vo.setPlayCount(played);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkVO publish(LiveNovelCreateRequest request) {
        Long userId = SecurityUtil.getUserId();
        String content = WorkRules.content(request.getContent());
        LiveNovel novel = new LiveNovel();
        novel.setUploaderId(userId);
        applyFields(novel, request, content);
        novel.setPlayCount(0);
        novel.setStatus(LiveNovel.STATUS_NORMAL);
        novelMapper.insert(novel);
        log.info("发布小说: novelId={}, uploaderId={}, wordCount={}", novel.getId(), userId, novel.getWordCount());
        return toVO(novel, userQuerySpi.getById(userId), userId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkVO edit(Long id, LiveNovelCreateRequest request) {
        LiveNovel novel = requireOwned(id);
        String content = WorkRules.content(request.getContent());
        applyFields(novel, request, content);
        novelMapper.updateById(novel);
        log.info("编辑小说: novelId={}, uploaderId={}, wordCount={}", id, novel.getUploaderId(), novel.getWordCount());
        return toVO(novel, userQuerySpi.getById(novel.getUploaderId()), novel.getUploaderId(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        LiveNovel novel = novelMapper.selectById(id);
        if (novel == null) {
            // 幂等：重复删除或已被他人删除时静默返回
            return;
        }
        requireOwned(novel);
        novelMapper.deleteById(id);
        log.info("删除小说: novelId={}, uploaderId={}", id, novel.getUploaderId());
    }

    @Override
    public void increasePlayCount(Long id) {
        novelMapper.update(null, new LambdaUpdateWrapper<LiveNovel>()
                .eq(LiveNovel::getId, id)
                .setSql("play_count = play_count + 1"));
    }

    /** 表单字段落到实体，POST 与 PUT 共用（整表单替换语义）；字数与字节数在这里现算 */
    private void applyFields(LiveNovel novel, LiveNovelCreateRequest request, String content) {
        novel.setTitle(WorkRules.title(request.getTitle(), BOARD_NAME));
        novel.setCategory(WorkRules.category(request.getCategory()));
        novel.setContent(content);
        novel.setWordCount(WorkRules.wordCount(content));
        novel.setSize(WorkRules.utf8Size(content));
        novel.setSummary(WorkRules.summary(request.getSummary(), content));
        if (request.getCoverFileId() != null) {
            novel.setCoverFileId(request.getCoverFileId());
            novel.setCoverUrl(accessUrls.of(request.getCoverFileId()));
        } else {
            novel.setCoverFileId(null);
            novel.setCoverUrl(null);
        }
    }

    /** 按 ID 取行并校验本人；记录不存在给 NOT_FOUND，非本人给 FORBIDDEN */
    private LiveNovel requireOwned(Long id) {
        LiveNovel novel = novelMapper.selectById(id);
        if (novel == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作品不存在或已被删除");
        }
        return requireOwned(novel);
    }

    /**
     * 归属校验。<b>编辑走这里而不是 {@link #detail} 那道状态闸</b>：作者改自己的稿子与「是否下架」
     * 无关，运营下架是针对信息流的展示，不该顺手锁掉作者的编辑器。
     */
    private LiveNovel requireOwned(LiveNovel novel) {
        Long userId = SecurityUtil.getUserId();
        if (!novel.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return novel;
    }

    /**
     * @param withContent 是否把正文放进 VO。<b>只有 {@link #detail} 传 true</b>；
     *                    其余路径即便内存里有正文也不回填，免得哪天误把整页序列化出去。
     */
    private WorkVO toVO(LiveNovel novel, UserBriefDTO brief, Long me, boolean withContent) {
        WorkVO vo = new WorkVO();
        vo.setId(novel.getId());
        vo.setType(BOARD);
        vo.setUploaderId(novel.getUploaderId());
        if (brief != null) {
            vo.setUploaderName(brief.getNickname());
            vo.setUploaderAvatar(brief.getAvatar());
        }
        vo.setTitle(novel.getTitle());
        vo.setCategory(novel.getCategory());
        vo.setSummary(novel.getSummary());
        vo.setCoverFileId(novel.getCoverFileId());
        vo.setCoverUrl(novel.getCoverUrl());
        vo.setWordCount(novel.getWordCount());
        vo.setSize(novel.getSize());
        vo.setPlayCount(novel.getPlayCount());
        vo.setStatus(novel.getStatus());
        vo.setCreateTime(novel.getCreateTime());
        vo.setUpdateTime(novel.getUpdateTime());
        vo.setMine(novel.ownedBy(me));
        if (withContent) {
            vo.setContent(novel.getContent());
        }
        return vo;
    }
}
