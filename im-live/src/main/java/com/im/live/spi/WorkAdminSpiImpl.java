package com.im.live.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminWorkCreateDTO;
import com.im.common.domain.AdminWorkDTO;
import com.im.common.domain.AdminWorkQueryDTO;
import com.im.common.domain.AdminWorkUpdateDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.WorkBoardType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminWorkSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.live.entity.LiveImage;
import com.im.live.entity.LiveMusic;
import com.im.live.entity.LiveNovel;
import com.im.live.mapper.LiveImageMapper;
import com.im.live.mapper.LiveMusicMapper;
import com.im.live.mapper.LiveNovelMapper;
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
 * 后台「图片 / 音乐 / 小说」作品管理契约的实现（{@link AdminWorkSpi}）。
 *
 * <p><b>一套实现吃三个板块，靠 {@code type} 分发</b>：三张表的列口径在 {@code sql/im_schema.sql}
 * 里刻意对齐（连计数列都统一叫 {@code play_count}），六件事的动作也同构，写成三份只会让校验口径
 * 从此各改各的。分发点只有 {@link #requireBoard(String)} 一处，{@code video} 与未知值都进不来。
 *
 * <p>本类<b>不复用用户端的 {@code LiveImageService} 等</b>：那三个服务的每一个写操作都夹着
 * 「必须本人」的归属校验与 {@code status=1} 的展示口径，而后台的职责恰恰是改别人的行、看已下架的行。
 * 硬要复用就得给它们开「跳过校验」的参数口子，那等于在用户端代码里埋一个权限旁路。
 * 两边共享的是 {@link WorkRules}（字段兜底与现算）与 {@link WorkAccessUrls}（受控地址），
 * 也就是真正会「两边算得不一样」的那部分。
 *
 * <p>后台代建的作品 {@code uploader_id = 操作管理员}：谁点的发布就在列表里显示谁，
 * 比伪装成一个普通用户更好对账。编辑不动归属、不动状态（状态走 {@link #setStatus}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkAdminSpiImpl implements AdminWorkSpi {

    /** 单页上限：后台一屏最多 100 行，与 {@code VideoAdminSpiImpl} 同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 关键词长度上限，防止一条 10KB 的 like 拖死全表扫 */
    private static final int KEYWORD_MAX = 50;
    /** 状态：正常 / 已下架，三张表同一个取值域（实体上的常量只是别名） */
    private static final int STATUS_NORMAL = 1;
    private static final int STATUS_OFF = 2;
    /** 后台「去掉封面」的约定值：{@code AdminWorkUpdateDTO.coverFileId} 的 null 已被「不改」占用 */
    private static final long CLEAR_COVER = 0L;

    private final LiveImageMapper imageMapper;
    private final LiveMusicMapper musicMapper;
    private final LiveNovelMapper novelMapper;
    private final UserQuerySpi userQuerySpi;
    private final WorkAccessUrls accessUrls;

    @Override
    public PageResult<AdminWorkDTO> page(AdminWorkQueryDTO query) {
        WorkBoardType board = requireBoard(query.getType());
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = normalizeKeyword(query.getKeyword());
        return switch (board) {
            case IMAGE -> pageImages(query, current, size, keyword);
            case MUSIC -> pageMusics(query, current, size, keyword);
            case NOVEL -> pageNovels(query, current, size, keyword);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "视频板块请走 /api/admin/video/**");
        };
    }

    @Override
    public AdminWorkDTO detail(String type, Long id) {
        WorkBoardType board = requireBoard(type);
        // 后台详情带正文（小说）与完整图集：编辑弹窗要回填，预览也要看全文。
        // 与用户端不同，这里不拒已下架的行——运营得能打开刚下架的那一条确认改什么。
        return switch (board) {
            case IMAGE -> {
                LiveImage row = requireRow(imageMapper.selectById(id), id, "图集");
                yield toDTO(row, briefOf(row.getUploaderId()));
            }
            case MUSIC -> {
                LiveMusic row = requireRow(musicMapper.selectById(id), id, "音乐");
                yield toDTO(row, briefOf(row.getUploaderId()));
            }
            case NOVEL -> {
                LiveNovel row = requireRow(novelMapper.selectById(id), id, "小说");
                yield toDTO(row, briefOf(row.getUploaderId()), true);
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "视频板块请走 /api/admin/video/**");
        };
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminWorkDTO create(String type, AdminWorkCreateDTO dto, Long operatorId) {
        WorkBoardType board = requireBoard(type);
        // URL 上的 {type} 是权威来源，body 里的 dto.type 只用于「两处不一致时以谁为准」的裁决
        AdminWorkDTO created = switch (board) {
            case IMAGE -> createImage(dto, operatorId);
            case MUSIC -> createMusic(dto, operatorId);
            case NOVEL -> createNovel(dto, operatorId);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "视频板块请走 /api/admin/video/**");
        };
        log.info("[后台作品管理] operator={} 在{}板块代建作品: id={}, uploaderId={}, title={}",
                operatorId, board.getDesc(), created.getId(), created.getUploaderId(), created.getTitle());
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminWorkDTO update(String type, AdminWorkUpdateDTO dto, Long id, Long operatorId) {
        WorkBoardType board = requireBoard(type);
        AdminWorkDTO updated = switch (board) {
            case IMAGE -> updateImage(dto, id);
            case MUSIC -> updateMusic(dto, id);
            case NOVEL -> updateNovel(dto, id);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "视频板块请走 /api/admin/video/**");
        };
        log.info("[后台作品管理] operator={} 修改{}作品 {}: title={}, category={}",
                operatorId, board.getDesc(), id, updated.getTitle(), updated.getCategory());
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminWorkDTO setStatus(String type, Long id, boolean online, Long operatorId, String reason) {
        WorkBoardType board = requireBoard(type);
        int target = online ? STATUS_NORMAL : STATUS_OFF;
        AdminWorkDTO result = switch (board) {
            case IMAGE -> {
                LiveImage row = requireRow(imageMapper.selectById(id), id, "图集");
                if (!Integer.valueOf(target).equals(row.getStatus())) {
                    LiveImage patch = new LiveImage();
                    patch.setId(row.getId());
                    patch.setStatus(target);
                    imageMapper.updateById(patch);
                    row.setStatus(target);
                }
                yield toDTO(row, briefOf(row.getUploaderId()));
            }
            case MUSIC -> {
                LiveMusic row = requireRow(musicMapper.selectById(id), id, "音乐");
                if (!Integer.valueOf(target).equals(row.getStatus())) {
                    LiveMusic patch = new LiveMusic();
                    patch.setId(row.getId());
                    patch.setStatus(target);
                    musicMapper.updateById(patch);
                    row.setStatus(target);
                }
                yield toDTO(row, briefOf(row.getUploaderId()));
            }
            case NOVEL -> {
                LiveNovel row = requireRow(novelMapper.selectById(id), id, "小说");
                if (!Integer.valueOf(target).equals(row.getStatus())) {
                    LiveNovel patch = new LiveNovel();
                    patch.setId(row.getId());
                    patch.setStatus(target);
                    novelMapper.updateById(patch);
                    row.setStatus(target);
                }
                yield toDTO(row, briefOf(row.getUploaderId()), false);
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "视频板块请走 /api/admin/video/**");
        };
        // 事由只进服务端日志：库里没有 reason 列，而「为什么下架」是事后追责时唯一想要的字段
        log.info("[后台作品管理] operator={} 将{}作品 {} 置为{}: uploaderId={}, title={}, reason={}",
                operatorId, board.getDesc(), id, online ? "上架" : "下架",
                result.getUploaderId(), result.getTitle(), TextUtil.isBlank(reason) ? "—" : reason);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String type, Long id, Long operatorId) {
        WorkBoardType board = requireBoard(type);
        // @TableLogic 逻辑删除：信息流与后台列表即刻不见，im_file 里的媒体本体一期不级联清理
        boolean existed = switch (board) {
            case IMAGE -> imageMapper.deleteById(id) > 0;
            case MUSIC -> musicMapper.deleteById(id) > 0;
            case NOVEL -> novelMapper.deleteById(id) > 0;
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "视频板块请走 /api/admin/video/**");
        };
        // 幂等：不存在（或已被本人删过）时静默返回成功，只把真删掉的那次记进日志
        if (existed) {
            log.info("[后台作品管理] operator={} 删除{}作品 {}", operatorId, board.getDesc(), id);
        }
    }

    /* ==================== 分页 ==================== */

    private PageResult<AdminWorkDTO> pageImages(AdminWorkQueryDTO query, long current, long size, String keyword) {
        LambdaQueryWrapper<LiveImage> wrapper = applyFilters(new LambdaQueryWrapper<LiveImage>(), query,
                LiveImage::getStatus, LiveImage::getUploaderId, LiveImage::getCategory);
        applyKeyword(wrapper, keyword, LiveImage::getTitle, LiveImage::getId, LiveImage::getUploaderId);
        wrapper.orderByDesc(LiveImage::getCreateTime);
        Page<LiveImage> result = imageMapper.selectPage(new Page<>(current, size), wrapper);
        if (result.getRecords().isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, UserBriefDTO> briefs = briefsOf(result.getRecords(), LiveImage::getUploaderId);
        List<AdminWorkDTO> dtos = new ArrayList<>(result.getRecords().size());
        for (LiveImage row : result.getRecords()) {
            dtos.add(toDTO(row, briefs.get(row.getUploaderId())));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private PageResult<AdminWorkDTO> pageMusics(AdminWorkQueryDTO query, long current, long size, String keyword) {
        LambdaQueryWrapper<LiveMusic> wrapper = applyFilters(new LambdaQueryWrapper<LiveMusic>(), query,
                LiveMusic::getStatus, LiveMusic::getUploaderId, LiveMusic::getCategory);
        applyKeyword(wrapper, keyword, LiveMusic::getTitle, LiveMusic::getId, LiveMusic::getUploaderId);
        wrapper.orderByDesc(LiveMusic::getCreateTime);
        Page<LiveMusic> result = musicMapper.selectPage(new Page<>(current, size), wrapper);
        if (result.getRecords().isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, UserBriefDTO> briefs = briefsOf(result.getRecords(), LiveMusic::getUploaderId);
        List<AdminWorkDTO> dtos = new ArrayList<>(result.getRecords().size());
        for (LiveMusic row : result.getRecords()) {
            dtos.add(toDTO(row, briefs.get(row.getUploaderId())));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private PageResult<AdminWorkDTO> pageNovels(AdminWorkQueryDTO query, long current, long size, String keyword) {
        // 与用户端同一道雷：列表绝不能带上 LONGTEXT 正文，用列名过滤以免日后加列时静默漏字段
        LambdaQueryWrapper<LiveNovel> wrapper = applyFilters(
                new LambdaQueryWrapper<LiveNovel>()
                        .select(LiveNovel.class, field -> !"content".equals(field.getColumn())),
                query, LiveNovel::getStatus, LiveNovel::getUploaderId, LiveNovel::getCategory);
        applyKeyword(wrapper, keyword, LiveNovel::getTitle, LiveNovel::getId, LiveNovel::getUploaderId);
        wrapper.orderByDesc(LiveNovel::getCreateTime);
        Page<LiveNovel> result = novelMapper.selectPage(new Page<>(current, size), wrapper);
        if (result.getRecords().isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, UserBriefDTO> briefs = briefsOf(result.getRecords(), LiveNovel::getUploaderId);
        List<AdminWorkDTO> dtos = new ArrayList<>(result.getRecords().size());
        for (LiveNovel row : result.getRecords()) {
            dtos.add(toDTO(row, briefs.get(row.getUploaderId()), false));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /* ==================== 新建 ==================== */

    private AdminWorkDTO createImage(AdminWorkCreateDTO dto, Long operatorId) {
        List<Long> ids = WorkRules.albumIds(dto.getImageIds());
        LiveImage image = new LiveImage();
        image.setUploaderId(operatorId);
        image.setTitle(WorkRules.title(dto.getTitle(), WorkBoardType.IMAGE.getDesc()));
        image.setCategory(WorkRules.category(dto.getCategory()));
        image.setImageIds(WorkRules.albumCsv(ids));
        image.setImageUrls(accessUrls.join(ids));
        image.setImageCount(ids.size());
        // 封面恒等于首图（见 LiveImage 类注释）：后台表单的 coverFileId 在本板块被忽略，
        // 否则「封面和第一张图不一样」会变成一条要人肉查库才能解释的工单
        image.setCoverFileId(ids.get(0));
        image.setCoverUrl(accessUrls.of(ids.get(0)));
        image.setSize(nonNegative(dto.getSize()));
        image.setExt(dto.getExt());
        image.setContentType(dto.getContentType());
        image.setPlayCount(0);
        image.setStatus(STATUS_NORMAL);
        imageMapper.insert(image);
        return toDTO(image, briefOf(operatorId));
    }

    private AdminWorkDTO createMusic(AdminWorkCreateDTO dto, Long operatorId) {
        BusinessException.throwIf(dto.getFileId() == null, ResultCode.BAD_REQUEST, "请先上传音频文件再提交");
        LiveMusic music = new LiveMusic();
        music.setUploaderId(operatorId);
        music.setTitle(WorkRules.title(dto.getTitle(), WorkBoardType.MUSIC.getDesc()));
        music.setCategory(WorkRules.category(dto.getCategory()));
        music.setFileId(dto.getFileId());
        music.setFileUrl(accessUrls.of(dto.getFileId()));
        applyCover(music, dto.getCoverFileId());
        music.setDuration(dto.getDuration() == null || dto.getDuration() < 0 ? 0 : dto.getDuration());
        music.setSize(nonNegative(dto.getSize()));
        music.setExt(dto.getExt());
        music.setContentType(dto.getContentType());
        music.setPlayCount(0);
        music.setStatus(STATUS_NORMAL);
        musicMapper.insert(music);
        return toDTO(music, briefOf(operatorId));
    }

    private AdminWorkDTO createNovel(AdminWorkCreateDTO dto, Long operatorId) {
        String content = WorkRules.content(dto.getContent());
        LiveNovel novel = new LiveNovel();
        novel.setUploaderId(operatorId);
        novel.setTitle(WorkRules.title(dto.getTitle(), WorkBoardType.NOVEL.getDesc()));
        novel.setCategory(WorkRules.category(dto.getCategory()));
        novel.setContent(content);
        novel.setWordCount(WorkRules.wordCount(content));
        novel.setSize(WorkRules.utf8Size(content));
        novel.setSummary(WorkRules.summary(dto.getSummary(), content));
        applyCover(novel, dto.getCoverFileId());
        novel.setPlayCount(0);
        novel.setStatus(STATUS_NORMAL);
        novelMapper.insert(novel);
        return toDTO(novel, briefOf(operatorId), true);
    }

    /* ==================== 编辑 ==================== */

    private AdminWorkDTO updateImage(AdminWorkUpdateDTO dto, Long id) {
        LiveImage image = requireRow(imageMapper.selectById(id), id, "图集");
        if (dto.getTitle() != null) {
            image.setTitle(WorkRules.title(dto.getTitle(), WorkBoardType.IMAGE.getDesc()));
        }
        if (dto.getCategory() != null) {
            image.setCategory(WorkRules.category(dto.getCategory()));
        }
        if (dto.getImageIds() != null) {
            // 整组替换：后台表单提交的就是「最终要展示的那几幅」，不做增量合并
            List<Long> ids = WorkRules.albumIds(dto.getImageIds());
            image.setImageIds(WorkRules.albumCsv(ids));
            image.setImageUrls(accessUrls.join(ids));
            image.setImageCount(ids.size());
            image.setCoverFileId(ids.get(0));
            image.setCoverUrl(accessUrls.of(ids.get(0)));
        }
        if (dto.getSize() != null && dto.getSize() >= 0) {
            image.setSize(dto.getSize());
        }
        if (TextUtil.isNotBlank(dto.getExt())) {
            image.setExt(dto.getExt());
        }
        if (TextUtil.isNotBlank(dto.getContentType())) {
            image.setContentType(dto.getContentType());
        }
        imageMapper.updateById(image);
        return toDTO(image, briefOf(image.getUploaderId()));
    }

    private AdminWorkDTO updateMusic(AdminWorkUpdateDTO dto, Long id) {
        LiveMusic music = requireRow(musicMapper.selectById(id), id, "音乐");
        if (dto.getTitle() != null) {
            music.setTitle(WorkRules.title(dto.getTitle(), WorkBoardType.MUSIC.getDesc()));
        }
        if (dto.getCategory() != null) {
            music.setCategory(WorkRules.category(dto.getCategory()));
        }
        if (dto.getFileId() != null) {
            music.setFileId(dto.getFileId());
            music.setFileUrl(accessUrls.of(dto.getFileId()));
        }
        if (dto.getCoverFileId() != null) {
            applyCover(music, dto.getCoverFileId());
        }
        if (dto.getDuration() != null && dto.getDuration() >= 0) {
            music.setDuration(dto.getDuration());
        }
        if (dto.getSize() != null && dto.getSize() >= 0) {
            music.setSize(dto.getSize());
        }
        if (TextUtil.isNotBlank(dto.getExt())) {
            music.setExt(dto.getExt());
        }
        if (TextUtil.isNotBlank(dto.getContentType())) {
            music.setContentType(dto.getContentType());
        }
        musicMapper.updateById(music);
        return toDTO(music, briefOf(music.getUploaderId()));
    }

    private AdminWorkDTO updateNovel(AdminWorkUpdateDTO dto, Long id) {
        LiveNovel novel = requireRow(novelMapper.selectById(id), id, "小说");
        if (dto.getTitle() != null) {
            novel.setTitle(WorkRules.title(dto.getTitle(), WorkBoardType.NOVEL.getDesc()));
        }
        if (dto.getCategory() != null) {
            novel.setCategory(WorkRules.category(dto.getCategory()));
        }
        if (dto.getContent() != null) {
            String content = WorkRules.content(dto.getContent());
            novel.setContent(content);
            // 字数与字节数是正文的派生值，改了正文不重算就是账实不符
            novel.setWordCount(WorkRules.wordCount(content));
            novel.setSize(WorkRules.utf8Size(content));
            // 摘要没同时传时：留空才从新正文重截取，作者手写过的摘要不该被后台改正文冲掉
            if (dto.getSummary() == null) {
                novel.setSummary(WorkRules.summary(novel.getSummary(), content));
            }
        }
        if (dto.getSummary() != null) {
            novel.setSummary(WorkRules.summary(dto.getSummary(), novel.getContent()));
        }
        if (dto.getCoverFileId() != null) {
            applyCover(novel, dto.getCoverFileId());
        }
        novelMapper.updateById(novel);
        return toDTO(novel, briefOf(novel.getUploaderId()), false);
    }

    /* ==================== 通用小工具 ==================== */

    /** 板块白名单：只有 image / music / novel 能从通用后台接口进来 */
    private WorkBoardType requireBoard(String type) {
        WorkBoardType board = WorkBoardType.ofAdminWork(type);
        if (board != null) {
            return board;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST,
                WorkBoardType.of(type) == WorkBoardType.VIDEO
                        ? "视频板块请走 /api/admin/video/**" : "未知的板块：" + type);
    }

    /**
     * 三个板块的过滤条件形状完全一致（状态 / 作者 / 分类），抽成一处：写在三处的第一个代价不是
     * 行数，而是「新加一个筛选条件时只改了两个板块」——那会体现为“图片列表能按作者筛、小说不能”，
     * 运营当成 bug 报上来。
     *
     * <p>{@code eq(condition, column, value)} 的形式：condition 为 false 时该条件根本不拼进 SQL，
     * 而不是拼一个 {@code = NULL}（后者永不命中）。
     */
    private <T> LambdaQueryWrapper<T> applyFilters(LambdaQueryWrapper<T> wrapper, AdminWorkQueryDTO query,
                                                   SFunction<T, Integer> statusColumn,
                                                   SFunction<T, Long> uploaderColumn,
                                                   SFunction<T, String> categoryColumn) {
        String category = TextUtil.isBlank(query.getCategory()) ? null : query.getCategory().trim();
        return wrapper
                .eq(query.getStatus() != null, statusColumn, query.getStatus())
                .eq(query.getUploaderId() != null, uploaderColumn, query.getUploaderId())
                .eq(category != null, categoryColumn, category);
    }

    /**
     * 关键词过滤：标题模糊匹配；纯数字时额外认「作品 ID 或上传者 ID 精确命中」。
     *
     * <p>三种命中方式并存是因为后台运营手里能拿到的标识就这三种（标题片段、工单里贴的作品 ID、
     * 用户 ID），让他们自己分清是哪一种只会变成反复试。数字上限 18 位防 {@code long} 溢出。
     */
    private <T> void applyKeyword(LambdaQueryWrapper<T> wrapper, String keyword,
                                  SFunction<T, String> titleColumn,
                                  SFunction<T, Long> idColumn,
                                  SFunction<T, Long> uploaderColumn) {
        if (keyword == null) {
            return;
        }
        final String kw = keyword;
        boolean numeric = kw.length() <= 18 && kw.chars().allMatch(Character::isDigit);
        wrapper.and(w -> {
            w.like(titleColumn, kw);
            if (numeric) {
                long value = Long.parseLong(kw);
                w.or().eq(idColumn, value).or().eq(uploaderColumn, value);
            }
        });
    }

    private String normalizeKeyword(String raw) {
        if (TextUtil.isBlank(raw)) {
            return null;
        }
        String keyword = raw.trim();
        return keyword.length() > KEYWORD_MAX ? keyword.substring(0, KEYWORD_MAX) : keyword;
    }

    /** 取行并校验存在；文案统一成「作品不存在或已被删除」，不区分板块也不泄露别的板块的 ID */
    private <T> T requireRow(T row, Long id, String boardName) {
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, boardName + "不存在或已被删除");
        }
        return row;
    }

    /** 封面落库：0 表示清空（{@code null} 在更新语义里已被「不改」占用） */
    private void applyCover(LiveMusic music, Long coverFileId) {
        if (coverFileId == null || coverFileId == CLEAR_COVER) {
            music.setCoverFileId(null);
            music.setCoverUrl(null);
            return;
        }
        music.setCoverFileId(coverFileId);
        music.setCoverUrl(accessUrls.of(coverFileId));
    }

    /** 同上，小说封面（bizType=novel_cover） */
    private void applyCover(LiveNovel novel, Long coverFileId) {
        if (coverFileId == null || coverFileId == CLEAR_COVER) {
            novel.setCoverFileId(null);
            novel.setCoverUrl(null);
            return;
        }
        novel.setCoverFileId(coverFileId);
        novel.setCoverUrl(accessUrls.of(coverFileId));
    }

    private Long nonNegative(Long size) {
        return size == null || size < 0 ? 0L : size;
    }

    /** 批量取作者信息，避免逐行查（N+1） */
    private <T> Map<Long, UserBriefDTO> briefsOf(List<T> rows, SFunction<T, Long> uploaderGetter) {
        Set<Long> ids = new HashSet<>();
        for (T row : rows) {
            Long uploaderId = uploaderGetter.apply(row);
            if (uploaderId != null) {
                ids.add(uploaderId);
            }
        }
        return userQuerySpi.listByIds(ids);
    }

    private UserBriefDTO briefOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return userQuerySpi.getById(userId);
    }

    /** 作者昵称：查不到给占位，不留 null（后台表格里空白一格会被当成渲染 bug 报上来） */
    private String uploaderName(UserBriefDTO brief) {
        if (brief == null) {
            return "已注销用户";
        }
        return TextUtil.isNotBlank(brief.getNickname()) ? brief.getNickname() : brief.getUsername();
    }

    private AdminWorkDTO toDTO(LiveImage image, UserBriefDTO brief) {
        return AdminWorkDTO.builder()
                .id(image.getId())
                .type(WorkBoardType.IMAGE.getCode())
                .title(image.getTitle())
                .category(image.getCategory())
                .uploaderId(image.getUploaderId())
                .uploaderName(uploaderName(brief))
                .imageIds(image.fileIdList())
                .imageUrls(image.urlList())
                .imageCount(image.getImageCount())
                .coverUrl(image.getCoverUrl())
                .size(image.getSize())
                .playCount(image.getPlayCount())
                .status(image.getStatus())
                .createTime(image.getCreateTime())
                .updateTime(image.getUpdateTime())
                .build();
    }

    private AdminWorkDTO toDTO(LiveMusic music, UserBriefDTO brief) {
        return AdminWorkDTO.builder()
                .id(music.getId())
                .type(WorkBoardType.MUSIC.getCode())
                .title(music.getTitle())
                .category(music.getCategory())
                .uploaderId(music.getUploaderId())
                .uploaderName(uploaderName(brief))
                .fileUrl(music.getFileUrl())
                .coverUrl(music.getCoverUrl())
                .duration(music.getDuration())
                .size(music.getSize())
                .playCount(music.getPlayCount())
                .status(music.getStatus())
                .createTime(music.getCreateTime())
                .updateTime(music.getUpdateTime())
                .build();
    }

    /**
     * @param withContent 只有 {@code detail} 传 true：列表恒不带 LONGTEXT 正文
     */
    private AdminWorkDTO toDTO(LiveNovel novel, UserBriefDTO brief, boolean withContent) {
        AdminWorkDTO.AdminWorkDTOBuilder builder = AdminWorkDTO.builder()
                .id(novel.getId())
                .type(WorkBoardType.NOVEL.getCode())
                .title(novel.getTitle())
                .category(novel.getCategory())
                .uploaderId(novel.getUploaderId())
                .uploaderName(uploaderName(brief))
                .summary(novel.getSummary())
                .coverUrl(novel.getCoverUrl())
                .wordCount(novel.getWordCount())
                .size(novel.getSize())
                .playCount(novel.getPlayCount())
                .status(novel.getStatus())
                .createTime(novel.getCreateTime())
                .updateTime(novel.getUpdateTime());
        if (withContent) {
            builder.content(novel.getContent());
        }
        return builder.build();
    }
}
