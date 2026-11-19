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
import com.im.live.dto.req.LiveVideoCreateRequest;
import com.im.live.dto.vo.LiveVideoVO;
import com.im.live.entity.LiveVideo;
import com.im.live.mapper.LiveVideoMapper;
import com.im.live.service.LiveVideoService;
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
 * 视频板块服务实现。
 *
 * <p>信息流是「所有人都能看」的场景，但这里存的只是元数据，真正的访问控制在 {@code im-file}：
 * 视频/封面上传时打了 {@code video}/{@code video_cover} 的公开 bizType，
 * {@code FileServiceImpl#canAccess} 对所有登录用户放行，因此列表返回的受控地址任何人都能拉。
 *
 * <p>受控地址由服务端按 {@code im.file.access-url-prefix} 现拼（{@link #accessUrl}）而不是采信前端
 * 传来的 URL：前端只能决定「我引用哪个 fileId」，拼出来的地址永远是本站的受控下载地址，杜绝了
 * 客户端塞一条外部直链或别的文件地址进来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveVideoServiceImpl implements LiveVideoService {

    private static final String DEFAULT_TITLE = "未命名视频";
    private static final int TITLE_MAX = 60;
    /** 分类缺省值：前端发布时不选分类就落这个，保证列 NOT NULL 且信息流「全部」能统一回显 */
    private static final String DEFAULT_CATEGORY = "综合";

    private final LiveVideoMapper videoMapper;
    private final UserQuerySpi userQuerySpi;
    private final ImProperties imProperties;

    @Override
    public Page<LiveVideoVO> page(long current, long size, String category, String keyword, String sort, boolean mine) {
        Page<LiveVideo> page = new Page<>(current, size);
        Long me = SecurityUtil.getUserIdOrNull();
        LambdaQueryWrapper<LiveVideo> wrapper = new LambdaQueryWrapper<LiveVideo>()
                .eq(LiveVideo::getStatus, LiveVideo.STATUS_NORMAL);
        // 二级分类：精确命中（前端分类标签来自固定枚举，不做 LIKE）
        if (TextUtil.isNotBlank(category)) {
            wrapper.eq(LiveVideo::getCategory, category.trim());
        }
        // 顶部搜索：标题模糊匹配；LIKE 的关键字里带 %/_ 不额外转义，最坏只是匹配宽一些，无注入风险（预编译参数）
        if (TextUtil.isNotBlank(keyword)) {
            wrapper.like(LiveVideo::getTitle, keyword.trim());
        }
        // 左侧「我的」频道：只看本人上传；未登录时该视图自然为空
        if (mine) {
            if (me == null) {
                Page<LiveVideoVO> empty = new Page<>(current, size, 0);
                empty.setRecords(List.of());
                return empty;
            }
            wrapper.eq(LiveVideo::getUploaderId, me);
        }
        // 排序：最热按播放量倒序（同量再按时间兜底），其余（精选/我的/分类）一律按发布时间倒序
        if ("hot".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(LiveVideo::getPlayCount).orderByDesc(LiveVideo::getCreateTime);
        } else {
            wrapper.orderByDesc(LiveVideo::getCreateTime);
        }
        Page<LiveVideo> result = videoMapper.selectPage(page, wrapper);

        Page<LiveVideoVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<LiveVideo> records = result.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Set<Long> uploaderIds = new HashSet<>();
        for (LiveVideo video : records) {
            uploaderIds.add(video.getUploaderId());
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(uploaderIds);
        List<LiveVideoVO> vos = new ArrayList<>(records.size());
        for (LiveVideo video : records) {
            vos.add(toVO(video, briefs.get(video.getUploaderId()), me));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LiveVideoVO create(LiveVideoCreateRequest request) {
        Long userId = SecurityUtil.getUserId();
        LiveVideo video = new LiveVideo();
        video.setUploaderId(userId);
        video.setTitle(resolveTitle(request.getTitle()));
        video.setCategory(resolveCategory(request.getCategory()));
        video.setFileId(request.getFileId());
        video.setFileUrl(accessUrl(request.getFileId()));
        if (request.getCoverFileId() != null) {
            video.setCoverFileId(request.getCoverFileId());
            video.setCoverUrl(accessUrl(request.getCoverFileId()));
        }
        video.setDuration(request.getDuration() == null || request.getDuration() < 0 ? 0 : request.getDuration());
        video.setWidth(request.getWidth());
        video.setHeight(request.getHeight());
        video.setSize(request.getSize() == null || request.getSize() < 0 ? 0L : request.getSize());
        video.setExt(request.getExt());
        video.setContentType(request.getContentType());
        video.setPlayCount(0);
        video.setStatus(LiveVideo.STATUS_NORMAL);
        videoMapper.insert(video);
        log.info("发布视频: videoId={}, fileId={}, uploaderId={}", video.getId(), video.getFileId(), userId);
        return toVO(video, userQuerySpi.getById(userId), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        LiveVideo video = videoMapper.selectById(id);
        if (video == null) {
            // 幂等：重复删除或已被他人删除时静默返回，不给前端弹「视频不存在」的误导提示
            return;
        }
        Long userId = SecurityUtil.getUserId();
        if (!video.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        // @TableLogic 逻辑删除：信息流列表与详情即刻消失，im_file 里的视频本体保留（可能被别处引用/便于恢复）
        videoMapper.deleteById(id);
        log.info("删除视频: videoId={}, uploaderId={}", id, userId);
    }

    @Override
    public void increasePlayCount(Long id) {
        // setSql 原子自增，不经「读-改-写」内存值，避免并发播放丢计数；这是展示统计，非强一致
        videoMapper.update(null, new LambdaUpdateWrapper<LiveVideo>()
                .eq(LiveVideo::getId, id)
                .setSql("play_count = play_count + 1"));
    }

    /**
     * 标题兜底：留空时给一个占位标题并截断到列宽，避免 NOT NULL 列插入失败或脏数据撑爆列。
     */
    private String resolveTitle(String title) {
        String value = TextUtil.isBlank(title) ? DEFAULT_TITLE : title.trim();
        return value.length() > TITLE_MAX ? value.substring(0, TITLE_MAX) : value;
    }

    /**
     * 分类兜底：留空给默认值并截断到列宽，与 {@link #resolveTitle} 同理（NOT NULL 列不接受脏长度）。
     */
    private String resolveCategory(String category) {
        String value = TextUtil.isBlank(category) ? DEFAULT_CATEGORY : category.trim();
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    /**
     * 拼受控访问地址：{@code im.file.access-url-prefix + "/" + fileId}，与 {@code im-file} 生成
     * {@code im_file.url} 用的是同一前缀，保证列表里的地址与下载接口完全对得上。
     */
    private String accessUrl(Long fileId) {
        return imProperties.getFile().getAccessUrlPrefix() + "/" + fileId;
    }

    private LiveVideoVO toVO(LiveVideo video, UserBriefDTO brief, Long me) {
        LiveVideoVO vo = new LiveVideoVO();
        vo.setId(video.getId());
        vo.setUploaderId(video.getUploaderId());
        if (brief != null) {
            vo.setUploaderName(brief.getNickname());
            vo.setUploaderAvatar(brief.getAvatar());
        }
        vo.setTitle(video.getTitle());
        vo.setCategory(video.getCategory());
        vo.setFileId(video.getFileId());
        vo.setFileUrl(video.getFileUrl());
        vo.setCoverFileId(video.getCoverFileId());
        vo.setCoverUrl(video.getCoverUrl());
        vo.setDuration(video.getDuration());
        vo.setWidth(video.getWidth());
        vo.setHeight(video.getHeight());
        vo.setSize(video.getSize());
        vo.setExt(video.getExt());
        vo.setContentType(video.getContentType());
        vo.setPlayCount(video.getPlayCount());
        vo.setStatus(video.getStatus());
        vo.setCreateTime(video.getCreateTime());
        vo.setMine(video.ownedBy(me));
        return vo;
    }
}
