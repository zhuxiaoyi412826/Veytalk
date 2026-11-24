package com.im.user.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminDatingQueryDTO;
import com.im.common.domain.AdminPhotoDTO;
import com.im.common.domain.AdminPhotoQueryDTO;
import com.im.common.domain.AdminProfileDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminDatingSpi;
import com.im.common.util.TextUtil;
import com.im.user.entity.User;
import com.im.user.entity.UserPhoto;
import com.im.user.entity.UserProfile;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserPhotoMapper;
import com.im.user.mapper.UserProfileMapper;
import com.im.user.service.DatingProfileService;
import com.im.user.service.DatingTagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 后台交友资料与照片审核契约的实现（{@link AdminDatingSpi}）。
 *
 * <p>类名带模块前缀的理由见 {@code AdminMetricsSpi} 接口注释——同接口的实现类在全上下文里
 * 必须类名唯一，否则扫包时 {@code ConflictingBeanDefinitionException}。
 *
 * <p>为什么不走 {@code DatingProfileService} / {@code DatingPhotoService} 的方法：那两个接口
 * 处处是「仅本人可改」的归属校验与「他人视角」的隐私收口（年龄隐藏、距离模糊化、待审照片不外发），
 * 而后台既要原始值又要跨用户写。把它们的方法复用过来，就等于在本类里开一条「跳过校验」的分支，
 * 而这条分支迟早被下一个功能当成用户端入口。本类只调 {@code recalcScore} 那种无视角的纯计算。
 *
 * <p>{@code quality_flag=2}（已升权）在本类是唯一写入点，与 {@link UserProfile#QUALITY_BOOSTED}
 * 的注释一致：自动重算永远不会把一个人顶到这一档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatingAdminSpiImpl implements AdminDatingSpi {

    /** 单页上限：后台一屏最多 100 行，与 {@code AdminUserSpi} 的约定同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 关键词长度上限，防止一条 10KB 的 like 拖死全表扫 */
    private static final int KEYWORD_MAX = 50;
    /**
     * 列表上每人最多带几个标签名。
     *
     * <p>截断只为控制一屏的文字量，不改变「谁选了哪些」这件事——全量在用户端资料卡上，
     * 后台的处置也从不需要读完一个人的 15 个标签。
     */
    private static final int TAG_PREVIEW = 6;
    /** 驳回原因缺省时给的一句话：不能让驳回行的原因为空，那样用户只知道被拦而不知为何 */
    private static final String DEFAULT_REJECT_REASON = "照片内容不符合社区规范";
    /** 驳回原因列宽（{@code reject_reason VARCHAR(255)}），超出即截断而不是报错 */
    private static final int REASON_MAX = 200;

    private final UserProfileMapper userProfileMapper;
    private final UserPhotoMapper userPhotoMapper;
    private final UserMapper userMapper;
    private final DatingProfileService datingProfileService;
    private final DatingTagService datingTagService;

    /* ==================== 交友资料 ==================== */

    @Override
    public PageResult<AdminProfileDTO> pageProfiles(AdminDatingQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = clampSize(query.getSize());
        String keyword = clampKeyword(query.getKeyword());

        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<UserProfile>()
                .eq(query.getUserId() != null, UserProfile::getUserId, query.getUserId())
                .eq(query.getDiscoverable() != null, UserProfile::getDiscoverable, query.getDiscoverable())
                .eq(query.getQualityFlag() != null, UserProfile::getQualityFlag, query.getQualityFlag())
                .ge(query.getMinScore() != null, UserProfile::getProfileScore, query.getMinScore())
                .like(TextUtil.isNotBlank(query.getCity()), UserProfile::getCity, trimToNull(query.getCity()));
        if (keyword != null) {
            // 数字关键词额外认「用户 ID 精确匹配」：运营从工单里拿到的是 uid，不会去猜城市名
            final String kw = keyword;
            boolean numeric = !kw.isEmpty() && kw.chars().allMatch(Character::isDigit);
            wrapper.and(w -> {
                w.like(UserProfile::getCity, kw).or().like(UserProfile::getOccupation, kw);
                if (numeric) {
                    w.or().eq(UserProfile::getUserId, Long.parseLong(kw));
                }
            });
        }
        // 活跃倒序：last_active_time 为 NULL 的人本来就没动过，MySQL 的 DESC 会把 NULL 落到最后，
        // 正好是想要的效果；再按 user_id 兜一层，否则同一秒活跃的人翻页顺序不稳定
        wrapper.orderByDesc(UserProfile::getLastActiveTime).orderByDesc(UserProfile::getUserId);

        Page<UserProfile> result = userProfileMapper.selectPage(new Page<>(current, size), wrapper);
        List<UserProfile> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (UserProfile row : rows) {
            userIds.add(row.getUserId());
        }
        Map<Long, User> users = loadUsers(userIds);
        Map<Long, Integer> approved = userPhotoMapper.countByAuditStatus(userIds, UserPhoto.AUDIT_PASSED);
        Map<Long, Integer> pending = userPhotoMapper.countByAuditStatus(userIds, UserPhoto.AUDIT_PENDING);
        Map<Long, List<String>> tags = datingTagService.namesByUsers(userIds);

        List<AdminProfileDTO> dtos = new ArrayList<>(rows.size());
        for (UserProfile row : rows) {
            dtos.add(toProfileDTO(row, users.get(row.getUserId()),
                    approved.getOrDefault(row.getUserId(), 0),
                    pending.getOrDefault(row.getUserId(), 0),
                    previewTags(tags.get(row.getUserId()))));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminProfileDTO setDiscoverable(Long userId, boolean discoverable, Long operatorId, String reason) {
        UserProfile profile = requireProfile(userId);
        int target = discoverable ? UserProfile.SHOW : UserProfile.HIDE;
        // 幂等：列已一致就不重复写库——列表刷新慢，连点两次「强制隐身」不该产出两条审计
        if (!Objects.equals(profile.getDiscoverable(), target)) {
            userProfileMapper.update(null, Wrappers.<UserProfile>lambdaUpdate()
                    .set(UserProfile::getDiscoverable, target)
                    .eq(UserProfile::getUserId, userId));
            profile.setDiscoverable(target);
        }
        log.info("[后台交友管理] operator={} 将用户 {} 的资料置为{}: city={}, reason={}",
                operatorId, userId, discoverable ? "可被发现" : "强制隐身",
                profile.getCity(), trimToNull(reason));
        return profileDTOOf(profile);
    }

    @Override
    public AdminProfileDTO setQualityFlag(Long userId, Integer qualityFlag, Long operatorId, String reason) {
        BusinessException.throwIf(qualityFlag == null || qualityFlag < UserProfile.QUALITY_NORMAL
                        || qualityFlag > UserProfile.QUALITY_BOOSTED,
                ResultCode.BAD_REQUEST, "质量标记只能是 0/1/2");
        UserProfile profile = requireProfile(userId);
        if (!Objects.equals(profile.getQualityFlag(), qualityFlag)) {
            userProfileMapper.update(null, Wrappers.<UserProfile>lambdaUpdate()
                    .set(UserProfile::getQualityFlag, qualityFlag)
                    .eq(UserProfile::getUserId, userId));
            profile.setQualityFlag(qualityFlag);
        }
        log.info("[后台交友管理] operator={} 将用户 {} 的质量标记置为{}: reason={}",
                operatorId, userId, qualityFlag, trimToNull(reason));
        return profileDTOOf(profile);
    }

    /* ==================== 照片审核 ==================== */

    @Override
    public PageResult<AdminPhotoDTO> pagePhotos(AdminPhotoQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = clampSize(query.getSize());
        String keyword = clampKeyword(query.getKeyword());
        Long byId = keyword != null && !keyword.isEmpty() && keyword.chars().allMatch(Character::isDigit)
                ? Long.parseLong(keyword) : null;
        Long userId = query.getUserId() != null ? query.getUserId() : byId;

        LambdaQueryWrapper<UserPhoto> wrapper = new LambdaQueryWrapper<UserPhoto>()
                .eq(query.getAuditStatus() != null, UserPhoto::getAuditStatus, query.getAuditStatus())
                .eq(userId != null, UserPhoto::getUserId, userId)
                // 升序：待审队列是先传来的先审，见 AdminPhotoQueryDTO 的类注释
                .orderByAsc(UserPhoto::getCreateTime)
                .orderByAsc(UserPhoto::getId);
        Page<UserPhoto> result = userPhotoMapper.selectPage(new Page<>(current, size), wrapper);
        List<UserPhoto> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (UserPhoto row : rows) {
            userIds.add(row.getUserId());
        }
        Map<Long, User> users = loadUsers(userIds);
        List<AdminPhotoDTO> dtos = new ArrayList<>(rows.size());
        for (UserPhoto row : rows) {
            dtos.add(toPhotoDTO(row, users.get(row.getUserId())));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminPhotoDTO auditPhoto(Long id, boolean pass, String reason, Long operatorId) {
        UserPhoto photo = requirePhoto(id);
        int target = pass ? UserPhoto.AUDIT_PASSED : UserPhoto.AUDIT_REJECTED;
        if (!Objects.equals(photo.getAuditStatus(), target)) {
            // 通过时把上一次的驳回原因一并清掉：留着会让用户在自己的照片墙上看到一句早已作废的拒绝
            userPhotoMapper.update(null, Wrappers.<UserPhoto>lambdaUpdate()
                    .set(UserPhoto::getAuditStatus, target)
                    .set(UserPhoto::getRejectReason, pass ? null : rejectReasonOf(reason))
                    .eq(UserPhoto::getId, id));
            photo.setAuditStatus(target);
            photo.setRejectReason(pass ? null : rejectReasonOf(reason));
            if (pass) {
                // 只有「变为通过」才动已通过张数，也就是只有这一刻需要重算完资料度；
                // 驳回与待审之间来回不改这个数，重算一次就是白写一行
                datingProfileService.recalcScore(photo.getUserId());
            }
        }
        log.info("[后台照片审核] operator={} 将照片 {} 置为{}: userId={}, reason={}",
                operatorId, id, pass ? "通过" : "驳回", photo.getUserId(), trimToNull(reason));
        return toPhotoDTO(photo, userMapper.selectById(photo.getUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePhoto(Long id, Long operatorId) {
        UserPhoto photo = userPhotoMapper.selectById(id);
        if (photo == null) {
            // 幂等：本人已删过的行再删一次不报错
            return;
        }
        userPhotoMapper.deleteById(id);
        // 删的正是主封面时不必修 is_main：封面规则是「主封面且已过审 → 否则第一张已过审的」，
        // 而被逻辑删的行根本进不了候选，下一次读封面就自动退到下一张
        datingProfileService.recalcScore(photo.getUserId());
        log.info("[后台照片审核] operator={} 删除照片 {}: userId={}, fileId={}",
                operatorId, id, photo.getUserId(), photo.getFileId());
    }

    /* ==================== 组装 ==================== */

    /** 资料行不存在就报错：后台不该为一个「没开过交友功能」的人造一份空档案。 */
    private UserProfile requireProfile(Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "userId 不能为空");
        UserProfile profile = userProfileMapper.selectById(userId);
        BusinessException.throwIf(profile == null, ResultCode.NOT_FOUND, "该用户没有交友资料，无需处置");
        return profile;
    }

    private UserPhoto requirePhoto(Long id) {
        BusinessException.throwIf(id == null, ResultCode.BAD_REQUEST, "照片 ID 不能为空");
        UserPhoto photo = userPhotoMapper.selectById(id);
        BusinessException.throwIf(photo == null, ResultCode.NOT_FOUND, "照片不存在或已被删除");
        return photo;
    }

    /** 一份档案的单人回读，只为 {@code setDiscoverable} / {@code setQualityFlag} 的返回值服务。 */
    private AdminProfileDTO profileDTOOf(UserProfile profile) {
        return toProfileDTO(profile, userMapper.selectById(profile.getUserId()),
                userPhotoMapper.countByAuditStatus(List.of(profile.getUserId()), UserPhoto.AUDIT_PASSED)
                        .getOrDefault(profile.getUserId(), 0),
                userPhotoMapper.countByAuditStatus(List.of(profile.getUserId()), UserPhoto.AUDIT_PENDING)
                        .getOrDefault(profile.getUserId(), 0),
                previewTags(datingTagService.namesByUsers(List.of(profile.getUserId())).get(profile.getUserId())));
    }

    /** 一次批量读账号，<b>含被封禁的</b>：后台要看得见「这个人被封了但资料还在」。 */
    private Map<Long, User> loadUsers(Collection<Long> userIds) {
        Map<Long, User> result = new HashMap<>(userIds.size());
        for (User user : userMapper.selectByIds(new ArrayList<>(userIds))) {
            result.put(user.getId(), user);
        }
        return result;
    }

    private static List<String> previewTags(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.size() <= TAG_PREVIEW ? List.copyOf(names) : List.copyOf(names.subList(0, TAG_PREVIEW));
    }

    private static String rejectReasonOf(String reason) {
        String text = TextUtil.isBlank(reason) ? DEFAULT_REJECT_REASON : reason.trim();
        return text.length() > REASON_MAX ? text.substring(0, REASON_MAX) : text;
    }

    private AdminProfileDTO toProfileDTO(UserProfile profile, User user, int photoCount, int pendingCount,
                                         List<String> tagNames) {
        return AdminProfileDTO.builder()
                .userId(profile.getUserId())
                .nickname(displayName(user))
                .username(user == null ? null : user.getUsername())
                .avatar(user == null ? null : user.getAvatar())
                .userStatus(user == null ? null : user.getStatus())
                .gender(user == null ? null : user.getGender())
                .birthday(profile.getBirthday())
                .heightCm(profile.getHeightCm())
                .education(profile.getEducation())
                .occupation(profile.getOccupation())
                .income(profile.getIncome())
                .marriage(profile.getMarriage())
                .datingGoal(profile.getDatingGoal())
                .province(profile.getProvince())
                .city(profile.getCity())
                .district(profile.getDistrict())
                .hasLocation(profile.hasFreshLocation())
                .momentVisibleDays(profile.getMomentVisibleDays())
                .discoverable(profile.getDiscoverable())
                .profileScore(profile.getProfileScore())
                .qualityFlag(profile.getQualityFlag())
                .photoCount(photoCount)
                .pendingPhotoCount(pendingCount)
                .tagNames(tagNames)
                .lastActiveTime(profile.getLastActiveTime())
                .createTime(profile.getCreateTime())
                .build();
    }

    private AdminPhotoDTO toPhotoDTO(UserPhoto photo, User user) {
        return AdminPhotoDTO.builder()
                .id(photo.getId())
                .userId(photo.getUserId())
                .userNickname(displayName(user))
                .fileId(photo.getFileId())
                .url(photo.getUrl())
                .sort(photo.getSort())
                .isMain(photo.getIsMain())
                .auditStatus(photo.getAuditStatus())
                .rejectReason(photo.getRejectReason())
                .width(photo.getWidth())
                .height(photo.getHeight())
                .size(photo.getSize())
                .createTime(photo.getCreateTime())
                .build();
    }

    /** 账号查不到（已注销）与昵称空都要有兜底：后台一行空名字看起来像渲染坏了。 */
    private static String displayName(User user) {
        if (user == null) {
            return "已注销用户";
        }
        return TextUtil.isNotBlank(user.getNickname()) ? user.getNickname() : user.getUsername();
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
