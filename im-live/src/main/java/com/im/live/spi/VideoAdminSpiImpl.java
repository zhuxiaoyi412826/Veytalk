package com.im.live.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminVideoDTO;
import com.im.common.domain.AdminVideoQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminVideoSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.live.entity.LiveVideo;
import com.im.live.mapper.LiveVideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台短视频管理契约的实现（{@link AdminVideoSpi}）。
 *
 * <p>类名带模块前缀的理由见 {@code AdminMetricsSpi} 接口注释——同接口的实现类
 * 在全上下文里必须类名唯一，否则扫包时 {@code ConflictingBeanDefinitionException}。
 *
 * <p>与用户端 {@code LiveVideoServiceImpl} 各自分页而不共享方法：那边恒定
 * {@code status=1} 且要补「是否本人」标志，这边要全状态且补上传者昵称，
 * 两套口径硬拧成一个方法，迟早改一边坏一边。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAdminSpiImpl implements AdminVideoSpi {

    /** 单页上限：后台一屏最多 100 行，与 AdminUserSpi 的约定同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 关键词长度上限，防止一条 10KB 的 like 拖死全表扫 */
    private static final int KEYWORD_MAX = 50;

    private final LiveVideoMapper videoMapper;
    private final UserQuerySpi userQuerySpi;

    @Override
    public PageResult<AdminVideoDTO> pageVideos(AdminVideoQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = TextUtil.isBlank(query.getKeyword()) ? null
                : query.getKeyword().trim();
        if (keyword != null && keyword.length() > KEYWORD_MAX) {
            keyword = keyword.substring(0, KEYWORD_MAX);
        }

        LambdaQueryWrapper<LiveVideo> wrapper = new LambdaQueryWrapper<LiveVideo>()
                .eq(query.getStatus() != null, LiveVideo::getStatus, query.getStatus())
                .eq(query.getUploaderId() != null, LiveVideo::getUploaderId, query.getUploaderId())
                .orderByDesc(LiveVideo::getCreateTime);
        if (keyword != null) {
            // 数字关键词额外认「上传者 ID 精确匹配」：运营从工单里拿到的是 uid，不会去猜昵称
            final String kw = keyword;
            boolean numeric = kw.chars().allMatch(Character::isDigit);
            wrapper.and(w -> {
                w.like(LiveVideo::getTitle, kw);
                if (numeric) {
                    w.or().eq(LiveVideo::getUploaderId, Long.parseLong(kw));
                }
            });
        }
        Page<LiveVideo> result = videoMapper.selectPage(new Page<>(current, size), wrapper);
        List<LiveVideo> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> uploaderIds = new HashSet<>();
        for (LiveVideo video : records) {
            if (video.getUploaderId() != null) {
                uploaderIds.add(video.getUploaderId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(uploaderIds);
        List<AdminVideoDTO> dtos = new ArrayList<>(records.size());
        for (LiveVideo video : records) {
            dtos.add(toDTO(video, briefs.get(video.getUploaderId())));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminVideoDTO setStatus(Long id, boolean online, Long operatorId, String reason) {
        LiveVideo video = videoMapper.selectById(id);
        if (video == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "视频不存在或已被删除");
        }
        int target = online ? LiveVideo.STATUS_NORMAL : LiveVideo.STATUS_OFF;
        // 幂等：状态已一致就不重复写库——后台列表刷新慢，连点两次「下架」不该产出两条审计
        if (!Integer.valueOf(target).equals(video.getStatus())) {
            LiveVideo patch = new LiveVideo();
            patch.setId(video.getId());
            patch.setStatus(target);
            videoMapper.updateById(patch);
            video.setStatus(target);
        }
        log.info("[后台视频管理] operator={} 将视频 {} 置为{}: uploaderId={}, title={}, reason={}",
                operatorId, video.getId(), online ? "上架" : "下架",
                video.getUploaderId(), video.getTitle(), TextUtil.isBlank(reason) ? "—" : reason);
        return toDTO(video, briefOf(video.getUploaderId()));
    }

    @Override
    public void delete(Long id, Long operatorId) {
        LiveVideo video = videoMapper.selectById(id);
        if (video == null) {
            // 幂等：本人已删过的行再删一次不报错
            return;
        }
        videoMapper.deleteById(id);
        log.info("[后台视频管理] operator={} 删除视频: id={}, uploaderId={}, title={}",
                operatorId, id, video.getUploaderId(), video.getTitle());
    }

    private UserBriefDTO briefOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return userQuerySpi.getById(userId);
    }

    private AdminVideoDTO toDTO(LiveVideo video, UserBriefDTO uploader) {
        String name = uploader == null ? "已注销用户"
                : (TextUtil.isNotBlank(uploader.getNickname()) ? uploader.getNickname() : uploader.getUsername());
        return AdminVideoDTO.builder()
                .id(video.getId())
                .uploaderId(video.getUploaderId())
                .uploaderName(name)
                .title(video.getTitle())
                .fileUrl(video.getFileUrl())
                .coverUrl(video.getCoverUrl())
                .duration(video.getDuration())
                .width(video.getWidth())
                .height(video.getHeight())
                .size(video.getSize())
                .ext(video.getExt())
                .playCount(video.getPlayCount() == null ? 0L : video.getPlayCount().longValue())
                .status(video.getStatus())
                .createTime(video.getCreateTime())
                .build();
    }
}
