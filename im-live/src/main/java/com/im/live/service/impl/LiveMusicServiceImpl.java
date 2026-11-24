package com.im.live.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.ResultCode;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.WorkBoardType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.live.dto.req.LiveMusicCreateRequest;
import com.im.live.dto.vo.WorkVO;
import com.im.live.entity.LiveMusic;
import com.im.live.mapper.LiveMusicMapper;
import com.im.live.service.LiveMusicService;
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
 * 音乐板块服务实现（音频信息流）。
 *
 * <p>与 {@code LiveVideoServiceImpl} 几乎同构：一行一首、本体在 {@code im_file}、受控地址服务端
 * 现拼。差别只有两处——没有画面尺寸（width/height 列压根没建），以及播放计数由前端在
 * {@code <audio>} 开始播放时上报。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveMusicServiceImpl implements LiveMusicService {

    /** VO 上回填的板块标识 */
    private static final String BOARD = WorkBoardType.MUSIC.getCode();
    /** 占位标题里的板块中文名 */
    private static final String BOARD_NAME = "音乐";

    private final LiveMusicMapper musicMapper;
    private final UserQuerySpi userQuerySpi;
    private final WorkAccessUrls accessUrls;

    @Override
    public Page<WorkVO> page(long current, long size, String category, String keyword, String sort, boolean mine) {
        Long me = SecurityUtil.getUserIdOrNull();
        Page<LiveMusic> page = new Page<>(current, size);
        LambdaQueryWrapper<LiveMusic> wrapper = new LambdaQueryWrapper<LiveMusic>()
                .eq(LiveMusic::getStatus, LiveMusic.STATUS_NORMAL);
        if (TextUtil.isNotBlank(category)) {
            wrapper.eq(LiveMusic::getCategory, category.trim());
        }
        if (TextUtil.isNotBlank(keyword)) {
            wrapper.like(LiveMusic::getTitle, keyword.trim());
        }
        if (mine) {
            if (me == null) {
                Page<WorkVO> empty = new Page<>(current, size, 0);
                empty.setRecords(List.of());
                return empty;
            }
            wrapper.eq(LiveMusic::getUploaderId, me);
        }
        // 排序：hot 按播放次数倒序（同量再按时间兜底），其余一律按发布时间倒序
        if ("hot".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(LiveMusic::getPlayCount).orderByDesc(LiveMusic::getCreateTime);
        } else {
            wrapper.orderByDesc(LiveMusic::getCreateTime);
        }
        Page<LiveMusic> result = musicMapper.selectPage(page, wrapper);

        Page<WorkVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<LiveMusic> records = result.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Set<Long> uploaderIds = new HashSet<>();
        for (LiveMusic music : records) {
            uploaderIds.add(music.getUploaderId());
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(uploaderIds);
        List<WorkVO> vos = new ArrayList<>(records.size());
        for (LiveMusic music : records) {
            vos.add(toVO(music, briefs.get(music.getUploaderId()), me));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkVO publish(LiveMusicCreateRequest request) {
        Long userId = SecurityUtil.getUserId();
        LiveMusic music = new LiveMusic();
        music.setUploaderId(userId);
        applyFields(music, request);
        music.setPlayCount(0);
        music.setStatus(LiveMusic.STATUS_NORMAL);
        musicMapper.insert(music);
        log.info("发布音乐: musicId={}, fileId={}, uploaderId={}", music.getId(), music.getFileId(), userId);
        return toVO(music, userQuerySpi.getById(userId), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkVO edit(Long id, LiveMusicCreateRequest request) {
        LiveMusic music = requireOwned(id);
        applyFields(music, request);
        musicMapper.updateById(music);
        log.info("编辑音乐: musicId={}, uploaderId={}", id, music.getUploaderId());
        return toVO(music, userQuerySpi.getById(music.getUploaderId()), music.getUploaderId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        LiveMusic music = musicMapper.selectById(id);
        if (music == null) {
            // 幂等：重复删除或已被他人删除时静默返回
            return;
        }
        requireOwned(music);
        musicMapper.deleteById(id);
        log.info("删除音乐: musicId={}, uploaderId={}", id, music.getUploaderId());
    }

    @Override
    public void increasePlayCount(Long id) {
        musicMapper.update(null, new LambdaUpdateWrapper<LiveMusic>()
                .eq(LiveMusic::getId, id)
                .setSql("play_count = play_count + 1"));
    }

    /**
     * 表单字段落到实体，POST 与 PUT 共用（PUT 是整表单替换，所以这里无脑覆盖）。
     *
     * <p>{@code coverFileId} 传 null 就是清空封面——这是把 PUT 定成「整条替换」换来的能力，
     * 也让前端编辑弹窗不必区分「我没动封面」和「我要去掉封面」。
     */
    private void applyFields(LiveMusic music, LiveMusicCreateRequest request) {
        music.setTitle(WorkRules.title(request.getTitle(), BOARD_NAME));
        music.setCategory(WorkRules.category(request.getCategory()));
        music.setFileId(request.getFileId());
        music.setFileUrl(accessUrls.of(request.getFileId()));
        if (request.getCoverFileId() != null) {
            music.setCoverFileId(request.getCoverFileId());
            music.setCoverUrl(accessUrls.of(request.getCoverFileId()));
        } else {
            music.setCoverFileId(null);
            music.setCoverUrl(null);
        }
        music.setDuration(request.getDuration() == null || request.getDuration() < 0 ? 0 : request.getDuration());
        music.setSize(request.getSize() == null || request.getSize() < 0 ? 0L : request.getSize());
        music.setExt(request.getExt());
        music.setContentType(request.getContentType());
    }

    /** 按 ID 取行并校验本人；记录不存在给 NOT_FOUND，非本人给 FORBIDDEN */
    private LiveMusic requireOwned(Long id) {
        LiveMusic music = musicMapper.selectById(id);
        if (music == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作品不存在或已被删除");
        }
        Long userId = SecurityUtil.getUserId();
        if (!music.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return music;
    }

    /** 归属校验（已有行的重载，供 delete 复用同一条判定） */
    private LiveMusic requireOwned(LiveMusic music) {
        Long userId = SecurityUtil.getUserId();
        if (!music.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return music;
    }

    private WorkVO toVO(LiveMusic music, UserBriefDTO brief, Long me) {
        WorkVO vo = new WorkVO();
        vo.setId(music.getId());
        vo.setType(BOARD);
        vo.setUploaderId(music.getUploaderId());
        if (brief != null) {
            vo.setUploaderName(brief.getNickname());
            vo.setUploaderAvatar(brief.getAvatar());
        }
        vo.setTitle(music.getTitle());
        vo.setCategory(music.getCategory());
        vo.setFileId(music.getFileId());
        vo.setFileUrl(music.getFileUrl());
        vo.setCoverFileId(music.getCoverFileId());
        vo.setCoverUrl(music.getCoverUrl());
        vo.setDuration(music.getDuration());
        vo.setSize(music.getSize());
        vo.setExt(music.getExt());
        vo.setContentType(music.getContentType());
        vo.setPlayCount(music.getPlayCount());
        vo.setStatus(music.getStatus());
        vo.setCreateTime(music.getCreateTime());
        vo.setUpdateTime(music.getUpdateTime());
        vo.setMine(music.ownedBy(me));
        return vo;
    }
}
