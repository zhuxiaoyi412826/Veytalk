package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.im.common.api.ResultCode;
import com.im.common.enums.DatingEducation;
import com.im.common.enums.DatingGoal;
import com.im.common.enums.DatingIncome;
import com.im.common.enums.DatingMarriage;
import com.im.common.enums.DatingSort;
import com.im.common.enums.ProfileVisitType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ProfileVisitSpi;
import com.im.common.util.GeoUtil;
import com.im.common.util.TextUtil;
import com.im.user.dto.req.DatingPrivacyRequest;
import com.im.user.dto.req.DatingProfileRequest;
import com.im.user.dto.vo.DatingDictVO;
import com.im.user.dto.vo.DatingProfileVO;
import com.im.user.entity.User;
import com.im.user.entity.UserProfile;
import com.im.user.entity.UserTag;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserPhotoMapper;
import com.im.user.mapper.UserTagMapper;
import com.im.user.mapper.UserProfileMapper;
import com.im.user.service.DatingProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 交友资料卡实现。
 *
 * <p>本类刻意依赖 {@link UserMapper} 而不是 {@code UserService}：{@code UserServiceImpl} 要读本表的
 * {@code show_online} 来收口资料卡上的在线状态，两边互相注入会直接把应用启动卡死（构造器循环依赖）。
 * 照片张数与标签个数同理走 {@link UserPhotoMapper}、{@link UserTagMapper} 而不是
 * {@code DatingPhotoService} / {@code DatingTagService}：那两个服务都要在这里调
 * {@code recalcScore}，双向注 service 就是同一个死结。
 *
 * <p>更新一律走 {@link LambdaUpdateWrapper} 逐列 {@code set}，不用 {@code updateById(patch)}：
 * 后者会跳过 null 字段，而「清空出生日期」这件事只能靠显式 {@code SET birthday = NULL} 表达。
 * {@code update_time} 因此拿不到 MyBatis-Plus 的自动填充，由 DDL 的
 * {@code ON UPDATE CURRENT_TIMESTAMP} 兜住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatingProfileServiceImpl implements DatingProfileService {

    /** clears 白名单：拼错一个字段名就静默不清空，是最难查的一类「改了没生效」，所以未知值必须报错 */
    private static final Set<String> CLEARABLE = Set.of(
            "birthday", "heightCm", "education", "occupation", "income", "marriage",
            "datingGoal", "province", "city", "district", "location");

    /** 动态可见范围只认这四个值，与 {@code im_user_profile.moment_visible_days} 的注释一一对应 */
    private static final Set<Integer> MOMENT_VISIBLE_OPTIONS = Set.of(0, 3, 7, 30);

    /**
     * 低于这个完资料度就判低质。35 分的含义是具体的：照片一张没有（-30）、没填生日（-10）时，
     * 剩下的结构化字段全填满也拿不到这个分——它圈住的是「几乎没填」而不是「填得不够完美」。
     */
    private static final int QUALITY_LOW_SCORE = 35;

    /** 新注册账号的降权保护期（天），口径见 {@link #qualityOf} */
    private static final int NEW_USER_SHIELD_DAYS = 7;

    private static final String CLEAR_BIRTHDAY = "birthday";
    private static final String CLEAR_HEIGHT = "heightCm";
    private static final String CLEAR_LOCATION = "location";

    private final UserProfileMapper userProfileMapper;
    private final UserPhotoMapper userPhotoMapper;
    private final UserTagMapper userTagMapper;
    private final UserMapper userMapper;
    /**
     * 访客足迹住在 im-friend，用 {@link ObjectProvider} 弱依赖取：构造期直接注会沿着
     * 「im-user → im-friend → {@code DatingQuerySpi} → im-user」把应用启动卡死
     * （同 {@code DatingQuerySpiImpl} 注 {@code FriendRelationSpi} 的处理）。
     */
    private final ObjectProvider<ProfileVisitSpi> profileVisitSpi;

    @Override
    public UserProfile getOrCreate(Long userId) {
        UserProfile profile = find(userId);
        if (profile != null) {
            return profile;
        }
        UserProfile fresh = new UserProfile();
        fresh.setUserId(userId);
        try {
            // 只塞主键，其余列交给建表时的默认值：这里写死的每一个默认值都会变成第二份口径
            userProfileMapper.insert(fresh);
        } catch (DuplicateKeyException e) {
            // 同一用户并发打开页面时会有两个请求同时建行，主键冲突说明另一个已经建好了
            log.debug("[交友资料] 并发补建资料行，采用已存在的行: userId={}", userId);
        }
        UserProfile loaded = userProfileMapper.selectById(userId);
        return loaded != null ? loaded : fresh;
    }

    @Override
    public UserProfile find(Long userId) {
        return userId == null ? null : userProfileMapper.selectById(userId);
    }

    @Override
    public Map<Long, UserProfile> findMap(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserProfile> result = new LinkedHashMap<>();
        for (UserProfile profile : userProfileMapper.selectBatchIds(userIds)) {
            result.put(profile.getUserId(), profile);
        }
        return result;
    }

    @Override
    public DatingProfileVO mine(Long userId) {
        UserProfile profile = getOrCreate(userId);
        User user = userMapper.selectById(userId);
        ScorePlan plan = plan(profile, user);
        return baseVO(profile)
                .userId(userId)
                .self(Boolean.TRUE)
                .birthday(profile.getBirthday())
                .age(GeoUtil.age(profile.getBirthday()))
                .locationFresh(profile.hasFreshLocation())
                .hasLocation(profile.getLng() != null && profile.getLat() != null)
                .momentVisibleDays(profile.getMomentVisibleDays())
                .showAge(profile.getShowAge())
                .showDistance(profile.getShowDistance())
                .showOnline(profile.getShowOnline())
                .discoverable(profile.getDiscoverable())
                .profileScore(plan.score())
                .missingTips(plan.tips())
                .build();
    }

    @Override
    public DatingProfileVO view(Long targetUserId, Long currentUserId) {
        UserProfile profile = find(targetUserId);
        if (profile == null) {
            // 没回填资料行的账号（例如 V1.0.7 之后注册且还没打开过交友页）给一张空卡，不报错
            return DatingProfileVO.builder()
                    .userId(targetUserId)
                    .self(Boolean.FALSE)
                    .educationDesc("")
                    .incomeDesc("")
                    .marriageDesc("")
                    .datingGoalDesc("")
                    .locationText("")
                    .build();
        }
        boolean ageVisible = profile.ageVisible();
        DatingProfileVO.DatingProfileVOBuilder builder = baseVO(profile)
                .userId(targetUserId)
                .self(Boolean.FALSE)
                .birthday(ageVisible ? profile.getBirthday() : null)
                .age(ageVisible ? GeoUtil.age(profile.getBirthday()) : null);
        // 距离只给模糊文案，且要求双方都在采信期内有定位：只有一方定位时算出来的距离是编的。
        // 这条收口规则本体在 UserProfile#distanceTextFrom，交友卡池走的是同一个方法，不在此处再写一遍
        // 查看者那一行只读一次：算距离与判隐身用的是同一行数据，读两次会得到两个结论
        UserProfile viewer = currentUserId == null || currentUserId.equals(targetUserId)
                ? null : find(currentUserId);
        if (viewer != null) {
            builder.distanceText(profile.distanceTextFrom(viewer));
            // 顺手在同一行 viewer 数据还握在手上时留痕，不再为它补一次 find()
            recordVisit(viewer, targetUserId);
        }
        return builder.build();
    }

    /**
     * 给对方留一次足迹，失败只记日志：资料已经给人看到了，足迹丢了只是少一条记录，
     * 不该因此让资料卡打不开。
     *
     * <p><b>隐身的人来看不落库</b>。这一条不是「少给一个功能」而是隐私口径的闭合：
     * 若隐身仍会留在对方的访客列表里，那么任何人只要开着一页资料卡等我来看，
     * 就能在 TA 自己的访客列表里发现我——隐身就只是一句只约束推荐流的话。
     *
     * <p>「看过照片墙 / 动态」不记，理由见 {@link ProfileVisitType}。
     */
    private void recordVisit(UserProfile viewer, Long targetUserId) {
        if (viewer.stealth()) {
            return;
        }
        ProfileVisitSpi spi = profileVisitSpi.getIfAvailable();
        if (spi == null) {
            // 只有把 im-friend 从聚合里裁掉时才会发生：交友卡仍然能看，只是不留足迹
            log.debug("[交友资料] 未装配访客服务，本次浏览不记足迹: visitorId={}", viewer.getUserId());
            return;
        }
        try {
            spi.record(viewer.getUserId(), targetUserId, ProfileVisitType.PROFILE_CARD);
        } catch (Exception e) {
            log.warn("[交友资料] 访客足迹写入失败，不影响资料卡返回: visitorId={}, targetUserId={}",
                    viewer.getUserId(), targetUserId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatingProfileVO save(Long userId, DatingProfileRequest request) {
        getOrCreate(userId);
        Set<String> clears = normalizeClears(request.getClears());
        LambdaUpdateWrapper<UserProfile> update = Wrappers.<UserProfile>lambdaUpdate()
                .eq(UserProfile::getUserId, userId);
        boolean changed = false;

        // 规则：传了新值就用新值；没传新值但字段名在 clears 里才清空；两者都没提就保持原样
        if (request.getBirthday() != null) {
            update.set(UserProfile::getBirthday, request.getBirthday());
            changed = true;
        } else if (clears.contains(CLEAR_BIRTHDAY)) {
            update.set(UserProfile::getBirthday, null);
            changed = true;
        }
        if (request.getHeightCm() != null) {
            update.set(UserProfile::getHeightCm, request.getHeightCm());
            changed = true;
        } else if (clears.contains(CLEAR_HEIGHT)) {
            update.set(UserProfile::getHeightCm, null);
            changed = true;
        }
        changed |= setEnum(update, UserProfile::getEducation, request.getEducation(),
                DatingEducation.of(request.getEducation()) != null, "学历");
        changed |= setEnum(update, UserProfile::getIncome, request.getIncome(),
                DatingIncome.of(request.getIncome()) != null, "收入区间");
        changed |= setEnum(update, UserProfile::getMarriage, request.getMarriage(),
                DatingMarriage.of(request.getMarriage()) != null, "婚恋状况");
        changed |= setEnum(update, UserProfile::getDatingGoal, request.getDatingGoal(),
                DatingGoal.of(request.getDatingGoal()) != null, "交友目的");
        changed |= setText(update, UserProfile::getOccupation, request.getOccupation(), clears, "occupation");
        changed |= setText(update, UserProfile::getProvince, request.getProvince(), clears, "province");
        changed |= setText(update, UserProfile::getCity, request.getCity(), clears, "city");
        changed |= setText(update, UserProfile::getDistrict, request.getDistrict(), clears, "district");
        changed |= applyLocation(update, request, clears);

        if (!changed) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有需要修改的字段");
        }
        // 改资料算一次活跃，「活跃度优先」的排序靠它
        update.set(UserProfile::getLastActiveTime, LocalDateTime.now());
        userProfileMapper.update(null, update);
        recalcScore(userId);
        return mine(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatingProfileVO savePrivacy(Long userId, DatingPrivacyRequest request) {
        getOrCreate(userId);
        UserProfile patch = new UserProfile();
        patch.setUserId(userId);
        boolean changed = false;
        if (request.getShowAge() != null) {
            patch.setShowAge(request.getShowAge());
            changed = true;
        }
        if (request.getShowDistance() != null) {
            patch.setShowDistance(request.getShowDistance());
            changed = true;
        }
        if (request.getShowOnline() != null) {
            patch.setShowOnline(request.getShowOnline());
            changed = true;
        }
        if (request.getDiscoverable() != null) {
            patch.setDiscoverable(request.getDiscoverable());
            changed = true;
        }
        if (request.getMomentVisibleDays() != null) {
            if (!MOMENT_VISIBLE_OPTIONS.contains(request.getMomentVisibleDays())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "动态可见范围只能为 0/3/7/30");
            }
            patch.setMomentVisibleDays(request.getMomentVisibleDays());
            changed = true;
        }
        if (!changed) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有需要修改的开关");
        }
        userProfileMapper.updateById(patch);
        return mine(userId);
    }

    @Override
    public DatingDictVO dict() {
        List<DatingDictVO.Option> educations = new ArrayList<>();
        for (DatingEducation value : DatingEducation.values()) {
            if (value != DatingEducation.UNSET) {
                educations.add(new DatingDictVO.Option(value.getCode(), value.getDesc()));
            }
        }
        List<DatingDictVO.Option> incomes = new ArrayList<>();
        for (DatingIncome value : DatingIncome.values()) {
            if (value != DatingIncome.UNSET) {
                incomes.add(new DatingDictVO.Option(value.getCode(), value.getDesc()));
            }
        }
        List<DatingDictVO.Option> marriages = new ArrayList<>();
        for (DatingMarriage value : DatingMarriage.values()) {
            if (value != DatingMarriage.UNSET) {
                marriages.add(new DatingDictVO.Option(value.getCode(), value.getDesc()));
            }
        }
        List<DatingDictVO.Option> goals = new ArrayList<>();
        for (DatingGoal value : DatingGoal.values()) {
            if (value != DatingGoal.UNSET) {
                goals.add(new DatingDictVO.Option(value.getCode(), value.getDesc()));
            }
        }
        return new DatingDictVO(educations, incomes, marriages, goals, sorts());
    }

    /**
     * 卡池可用的排序口径，直接由 {@link DatingSort} 生成。
     *
     * <p><b>不包 {@code distance}</b>：那一档只在「附近的人」里有意义（那里它不是选项而是固定口径），
     * 放进卡池字典就是一个选了也无效的选项。
     *
     * <p>为什么连排序也要下发：前端自己列一遍这四个字串，就会出现「后端支持 hot 而前端没有这个按钮」
     * 与「前端多了一个后端认不出的值」——与本类开头那四个字典同一个理由。
     */
    private static List<DatingDictVO.SortOption> sorts() {
        List<DatingDictVO.SortOption> result = new ArrayList<>();
        for (DatingSort value : DatingSort.values()) {
            if (value != DatingSort.DISTANCE) {
                result.add(new DatingDictVO.SortOption(value.getCode(), value.getDesc()));
            }
        }
        return result;
    }

    @Override
    public int recalcScore(Long userId) {
        UserProfile profile = find(userId);
        if (profile == null) {
            return 0;
        }
        User user = userMapper.selectById(userId);
        ScorePlan plan = plan(profile, user);
        int score = plan.score();
        int quality = qualityOf(profile, user, score, plan.photos());
        // 分数与质量标记同一条 UPDATE：拆成两次写就会出现「分数已经掉下来但还在推荐流里」的中间态
        if (!Objects.equals(profile.getProfileScore(), score)
                || !Objects.equals(nz(profile.getQualityFlag()), quality)) {
            userProfileMapper.update(null, Wrappers.<UserProfile>lambdaUpdate()
                    .eq(UserProfile::getUserId, userId)
                    .set(UserProfile::getProfileScore, score)
                    .set(UserProfile::getQualityFlag, quality));
        }
        return score;
    }

    /**
     * 推荐质量标记：这是「低质号自动降权」唯一的写入点。
     *
     * <p><b>只会落在 0（正常）与 1（低质）上</b>。2（升权）只由后台人工认证写（第十三批），
     * 这里如果按分数往上顶，就会出现「资料填全了自己变成加权号」——分数能自己刷，认证不能。
     *
     * <p>判据是「分数低 <b>或</b> 一张已过审照片都没有」：后者单独就能判低质，因为照片占了 30 分，
     * 一个只填文字不上传的人分数看着并不低，但在卡池里几乎无人会滑。
     *
     * <p>新注册 {@value #NEW_USER_SHIELD_DAYS} 天豁免：降权的目的不是惩罚，而是「不要把有限的新面孔
     * 位置浪费在填不满的人身上」，而新号还没被浪费过——同一段保护期与「冷启动曝光池」是一回事。
     *
     * <p>已升权的不自动降回去：认证是一次人工判断，不应当被用户自己改个头像冲掉。
     */
    private static int qualityOf(UserProfile profile, User user, int score, int photos) {
        if (nz(profile.getQualityFlag()) == UserProfile.QUALITY_BOOSTED) {
            return UserProfile.QUALITY_BOOSTED;
        }
        if (user != null && user.getCreateTime() != null
                && user.getCreateTime().isAfter(LocalDateTime.now().minusDays(NEW_USER_SHIELD_DAYS))) {
            return UserProfile.QUALITY_NORMAL;
        }
        return score < QUALITY_LOW_SCORE || photos == 0 ? UserProfile.QUALITY_LOW : UserProfile.QUALITY_NORMAL;
    }

    @Override
    public void touchActive(Long userId) {
        if (userId == null) {
            return;
        }
        userProfileMapper.update(null, Wrappers.<UserProfile>lambdaUpdate()
                .eq(UserProfile::getUserId, userId)
                .set(UserProfile::getLastActiveTime, LocalDateTime.now()));
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 完资料度与待补提示，同一个方法里算出来，避免「分数按一套口径、提示按另一套」。
     *
     * <p>权重：照片 30（满 3 张）> 标签 15（满 5 个）> 生日 10 > 其余结构化字段各 5
     * （身高 / 学历 / 职业 / 收入 / 婚恋状况 / 交友目的 / 城市）+ 头像 5 + 签名 5 = 100。
     * 照片占最大头是产品判断：交友里先看脸，这一点否认它也不会变成先看简历。
     */
    private ScorePlan plan(UserProfile profile, User user) {
        int score = 0;
        List<String> tips = new ArrayList<>();

        int photos = userPhotoMapper.countApproved(profile.getUserId());
        int photoScore = (int) Math.round(30d * Math.min(photos, 3) / 3);
        score += photoScore;
        if (photoScore < 30) {
            tips.add("上传生活照，凑满 3 张拿满 30 分");
        }

        int tags = userTagMapper.countByUserId(profile.getUserId());
        int tagScore = (int) Math.round(15d * Math.min(tags, UserTag.SCORE_FULL) / UserTag.SCORE_FULL);
        score += tagScore;
        if (tagScore < 15) {
            tips.add("选 " + UserTag.SCORE_FULL + " 个兴趣标签，还能拿 15 分");
        }

        if (profile.getBirthday() != null) {
            score += 10;
        } else {
            tips.add("补上出生日期");
        }
        score += plus(profile.getHeightCm() != null, tips, "填身高");
        score += plus(nz(profile.getEducation()) > 0, tips, "选学历");
        score += plus(TextUtil.isNotBlank(profile.getOccupation()), tips, "填职业");
        score += plus(nz(profile.getIncome()) > 0, tips, "选收入区间");
        score += plus(nz(profile.getMarriage()) > 0, tips, "选婚恋状况");
        score += plus(nz(profile.getDatingGoal()) > 0, tips, "选交友目的");
        score += plus(TextUtil.isNotBlank(profile.getCity()), tips, "填所在城市");
        score += plus(user != null && TextUtil.isNotBlank(user.getAvatar()), tips, "上传头像");
        score += plus(user != null && TextUtil.isNotBlank(user.getSignature()), tips, "写一句签名");
        return new ScorePlan(Math.min(100, score), tips, photos);
    }

    private DatingProfileVO.DatingProfileVOBuilder baseVO(UserProfile profile) {
        return DatingProfileVO.builder()
                .heightCm(profile.getHeightCm())
                .education(profile.getEducation())
                .educationDesc(DatingEducation.descOf(profile.getEducation()))
                .occupation(profile.getOccupation())
                .income(profile.getIncome())
                .incomeDesc(DatingIncome.descOf(profile.getIncome()))
                .marriage(profile.getMarriage())
                .marriageDesc(DatingMarriage.descOf(profile.getMarriage()))
                .datingGoal(profile.getDatingGoal())
                .datingGoalDesc(DatingGoal.descOf(profile.getDatingGoal()))
                .province(profile.getProvince())
                .city(profile.getCity())
                .district(profile.getDistrict())
                .locationText(profile.locationText());
    }

    private Set<String> normalizeClears(List<String> clears) {
        if (clears == null || clears.isEmpty()) {
            return Set.of();
        }
        for (String name : clears) {
            if (name == null || !CLEARABLE.contains(name.trim())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "未知的清空字段：" + name);
            }
        }
        return clears.stream().map(String::trim).collect(Collectors.toSet());
    }

    /**
     * 枚举列：{@code null} 不改；传 0 是「清为未填」，直接落库 0，不走 clears。
     *
     * <p>未匹配到枚举的取值一律拒掉而不是落成 0：写 0 等于把「非法输入」悄悄变成「用户选了未填」，
     * 用户下次打开表单会看到一个自己没填过的状态。
     */
    private boolean setEnum(LambdaUpdateWrapper<UserProfile> update,
                            SFunction<UserProfile, Integer> column,
                            Integer value, boolean valid, String label) {
        if (value == null) {
            return false;
        }
        if (!valid) {
            throw new BusinessException(ResultCode.BAD_REQUEST, label + "取值超出范围");
        }
        update.set(column, value);
        return true;
    }

    /** 文本列：传空串等同清空，避免「删掉职业里的字再保存」返回成功但库里还是旧值。 */
    private boolean setText(LambdaUpdateWrapper<UserProfile> update,
                            SFunction<UserProfile, String> column,
                            String value, Set<String> clears, String name) {
        if (value != null) {
            String cleaned = TextUtil.sanitize(value.trim(), 32);
            update.set(column, TextUtil.isBlank(cleaned) ? null : cleaned);
            return true;
        }
        if (clears.contains(name)) {
            update.set(column, null);
            return true;
        }
        return false;
    }

    /**
     * 定位：经纬度必须成对出现，落库时顺手重算 geohash 与定位时间。
     *
     * <p>geohash 由这里算、不接受前端传值：它必须与坐标同源，两边各算一次迟早会对不上，
     * 而对不上的表现是「本人永远刷不到自己附近的人」。
     */
    private boolean applyLocation(LambdaUpdateWrapper<UserProfile> update, DatingProfileRequest request,
                                  Set<String> clears) {
        if (request.getLng() == null && request.getLat() == null) {
            if (clears.contains(CLEAR_LOCATION)) {
                update.set(UserProfile::getLng, null)
                        .set(UserProfile::getLat, null)
                        .set(UserProfile::getGeohash, null)
                        .set(UserProfile::getLocationTime, null);
                return true;
            }
            return false;
        }
        BigDecimal lng = request.getLng();
        BigDecimal lat = request.getLat();
        Double lngValue = toDouble(lng);
        Double latValue = toDouble(lat);
        if (lngValue == null || latValue == null || !GeoUtil.isValid(lngValue, latValue)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "经纬度必须成对提供且在有效范围内");
        }
        update.set(UserProfile::getLng, lng)
                .set(UserProfile::getLat, lat)
                .set(UserProfile::getGeohash, GeoUtil.geohash(lngValue, latValue, GeoUtil.DEFAULT_PRECISION))
                .set(UserProfile::getLocationTime, LocalDateTime.now());
        return true;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private int plus(boolean filled, List<String> tips, String tip) {
        if (filled) {
            return 5;
        }
        tips.add(tip);
        return 0;
    }

    /**
     * 分数、提示与已过审照片数一起返回：三者必须出自同一次计算。
     *
     * <p>照片数单独透出来，是为了 {@code qualityOf} 能判「一张都没有」而不再查一次数据库——
     * 质量标记与分数本来就是同一条 UPDATE 写的，多一次查就是多一个对不上的机会。
     */
    private record ScorePlan(int score, List<String> tips, int photos) {
    }
}
