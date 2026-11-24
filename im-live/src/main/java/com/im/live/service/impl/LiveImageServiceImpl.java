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
import com.im.live.dto.req.LiveImageCreateRequest;
import com.im.live.dto.vo.WorkVO;
import com.im.live.entity.LiveImage;
import com.im.live.mapper.LiveImageMapper;
import com.im.live.service.LiveImageService;
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
 * 图片板块服务实现（图集信息流）。
 *
 * <p>与 {@code LiveVideoServiceImpl} 同构，只多一件事：把前端传来的 {@code imageIds}
 * 规范化成两个同序的逗号并列串（{@code image_ids} / {@code image_urls}），并把首图冗余成封面。
 *
 * <p>受控地址一律服务端现拼（{@link WorkAccessUrls}），<b>不采信前端传来的 URL</b>；
 * 张数越界（0 张或 >9 张）直接 {@code BAD_REQUEST}，而不是悄悄留一张或截断——用户选了就是一组，
 * 少一张是数据丢失。
 *
 * <p>{@code im_file} 里的图片本体不随删除而清理（与视频板块同口径：可能被别处引用、也便于恢复），
 * 一期不做级联清道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveImageServiceImpl implements LiveImageService {

    /** VO 上要回填的板块标识，取自 {@code WorkBoardType}，与后台接口的 type 参数同一取值域 */
    private static final String BOARD = WorkBoardType.IMAGE.getCode();
    /** 占位标题里的板块中文名 */
    private static final String BOARD_NAME = "图片";

    private final LiveImageMapper imageMapper;
    private final UserQuerySpi userQuerySpi;
    private final WorkAccessUrls accessUrls;

    @Override
    public Page<WorkVO> page(long current, long size, String category, String keyword, String sort, boolean mine) {
        Long me = SecurityUtil.getUserIdOrNull();
        Page<LiveImage> page = new Page<>(current, size);
        LambdaQueryWrapper<LiveImage> wrapper = new LambdaQueryWrapper<LiveImage>()
                .eq(LiveImage::getStatus, LiveImage.STATUS_NORMAL);
        // 二级分类：精确命中（分类标签来自后台字典，不做 LIKE）
        if (TextUtil.isNotBlank(category)) {
            wrapper.eq(LiveImage::getCategory, category.trim());
        }
        // 顶部搜索：标题模糊匹配；关键字里的 % 与 _ 不额外转义（预编译参数，最坏只是匹配宽一些）
        if (TextUtil.isNotBlank(keyword)) {
            wrapper.like(LiveImage::getTitle, keyword.trim());
        }
        if (mine) {
            if (me == null) {
                // 「我的」频道未登录时给空页而不是 401：与视频板块一致，允许匿名逛信息流
                Page<WorkVO> empty = new Page<>(current, size, 0);
                empty.setRecords(List.of());
                return empty;
            }
            wrapper.eq(LiveImage::getUploaderId, me);
        }
        // 排序：hot 按打开次数倒序（同量再按时间兜底），其余（精选/我的/分类）一律按发布时间倒序
        if ("hot".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(LiveImage::getPlayCount).orderByDesc(LiveImage::getCreateTime);
        } else {
            wrapper.orderByDesc(LiveImage::getCreateTime);
        }
        Page<LiveImage> result = imageMapper.selectPage(page, wrapper);

        Page<WorkVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<LiveImage> records = result.getRecords();
        if (records.isEmpty()) {
            voPage.setRecords(List.of());
            return voPage;
        }
        Set<Long> uploaderIds = new HashSet<>();
        for (LiveImage image : records) {
            uploaderIds.add(image.getUploaderId());
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(uploaderIds);
        List<WorkVO> vos = new ArrayList<>(records.size());
        for (LiveImage image : records) {
            vos.add(toVO(image, briefs.get(image.getUploaderId()), me));
        }
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkVO publish(LiveImageCreateRequest request) {
        Long userId = SecurityUtil.getUserId();
        List<Long> ids = WorkRules.albumIds(request.getImageIds());
        LiveImage image = new LiveImage();
        image.setUploaderId(userId);
        image.setTitle(WorkRules.title(request.getTitle(), BOARD_NAME));
        image.setCategory(WorkRules.category(request.getCategory()));
        applyAlbum(image, ids);
        image.setSize(request.getSize() == null || request.getSize() < 0 ? 0L : request.getSize());
        image.setExt(request.getExt());
        image.setContentType(request.getContentType());
        image.setPlayCount(0);
        image.setStatus(LiveImage.STATUS_NORMAL);
        imageMapper.insert(image);
        log.info("发布图集: imageId={}, uploaderId={}, count={}", image.getId(), userId, image.getImageCount());
        return toVO(image, userQuerySpi.getById(userId), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkVO edit(Long id, LiveImageCreateRequest request) {
        LiveImage image = requireOwned(id);
        List<Long> ids = WorkRules.albumIds(request.getImageIds());
        image.setTitle(WorkRules.title(request.getTitle(), BOARD_NAME));
        image.setCategory(WorkRules.category(request.getCategory()));
        applyAlbum(image, ids);
        if (request.getSize() != null && request.getSize() >= 0) {
            image.setSize(request.getSize());
        }
        if (TextUtil.isNotBlank(request.getExt())) {
            image.setExt(request.getExt());
        }
        if (TextUtil.isNotBlank(request.getContentType())) {
            image.setContentType(request.getContentType());
        }
        imageMapper.updateById(image);
        log.info("编辑图集: imageId={}, uploaderId={}, count={}", id, image.getUploaderId(), image.getImageCount());
        return toVO(image, userQuerySpi.getById(image.getUploaderId()), image.getUploaderId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        LiveImage image = imageMapper.selectById(id);
        if (image == null) {
            // 幂等：重复删除或已被他人删除时静默返回，不给前端弹「作品不存在」的误导提示
            return;
        }
        requireOwned(image);
        // @TableLogic 逻辑删除：信息流即刻不见，im_file 里的图片本体保留
        imageMapper.deleteById(id);
        log.info("删除图集: imageId={}, uploaderId={}", id, image.getUploaderId());
    }

    @Override
    public void increasePlayCount(Long id) {
        // setSql 原子自增，不经「读-改-写」内存值，避免并发点开丢计数；这是展示统计，非强一致
        imageMapper.update(null, new LambdaUpdateWrapper<LiveImage>()
                .eq(LiveImage::getId, id)
                .setSql("play_count = play_count + 1"));
    }

    /** 把图集落到实体：并列串 + 张数 + 首图冗余成封面（三者必须同一次写，否则会出现「封面不是首图」） */
    private void applyAlbum(LiveImage image, List<Long> ids) {
        Long first = ids.get(0);
        image.setImageIds(WorkRules.albumCsv(ids));
        image.setImageUrls(accessUrls.join(ids));
        image.setImageCount(ids.size());
        image.setCoverFileId(first);
        image.setCoverUrl(accessUrls.of(first));
    }

    /** 按 ID 取行并校验本人；记录不存在给 NOT_FOUND，非本人给 FORBIDDEN */
    private LiveImage requireOwned(Long id) {
        LiveImage image = imageMapper.selectById(id);
        if (image == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "作品不存在或已被删除");
        }
        return requireOwned(image);
    }

    /**
     * 归属校验放在服务端：前端的「编辑/删除」按钮只是不给看，直接调接口才是真正的一门。
     * 错误文案刻意含糊（不说「这是别人的作品」），免得把「某个 ID 存在但属于别人」当成可枚举信息。
     */
    private LiveImage requireOwned(LiveImage image) {
        Long userId = SecurityUtil.getUserId();
        if (!image.ownedBy(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return image;
    }

    private WorkVO toVO(LiveImage image, UserBriefDTO brief, Long me) {
        WorkVO vo = new WorkVO();
        vo.setId(image.getId());
        vo.setType(BOARD);
        vo.setUploaderId(image.getUploaderId());
        if (brief != null) {
            vo.setUploaderName(brief.getNickname());
            vo.setUploaderAvatar(brief.getAvatar());
        }
        vo.setTitle(image.getTitle());
        vo.setCategory(image.getCategory());
        vo.setImageIds(image.fileIdList());
        vo.setImageUrls(image.urlList());
        vo.setImageCount(image.getImageCount());
        vo.setCoverFileId(image.getCoverFileId());
        vo.setCoverUrl(image.getCoverUrl());
        vo.setSize(image.getSize());
        vo.setExt(image.getExt());
        vo.setContentType(image.getContentType());
        vo.setPlayCount(image.getPlayCount());
        vo.setStatus(image.getStatus());
        vo.setCreateTime(image.getCreateTime());
        vo.setUpdateTime(image.getUpdateTime());
        vo.setMine(image.ownedBy(me));
        return vo;
    }
}
