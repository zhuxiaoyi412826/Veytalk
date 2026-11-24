package com.im.friend.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.common.enums.DatingEducation;
import com.im.common.enums.DatingMarriage;
import com.im.common.enums.DatingSort;
import com.im.common.exception.BusinessException;
import com.im.friend.dto.req.PreferenceRequest;
import com.im.friend.dto.vo.PreferenceVO;
import com.im.friend.entity.MatchPreference;
import com.im.friend.mapper.MatchPreferenceMapper;
import com.im.friend.service.MatchPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 筛选与偏好实现。
 *
 * <p>本类只负责「存下来」与「摊出去」两件事，<b>不自己解释任何一条条件的 SQL 含义</b>：
 * 年龄要变成出生日期区间、学历要变成 {@code >=}、城市要变成包含匹配，这些都属于
 * {@code im_user_profile} 的所有者 im-user。在这里把「25 岁」算成「1991 年出生」，
 * 就是让两个模块各自持有一份会随时间漂移的口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchPreferenceServiceImpl implements MatchPreferenceService {

    /** 城市至少两个字：单个字（「州」「市」）会命中一大片不相干的城市，那是筛选条件是假的 */
    private static final int CITY_MIN_LENGTH = 2;

    private final MatchPreferenceMapper matchPreferenceMapper;

    @Override
    public PreferenceVO get(Long userId) {
        MatchPreference preference = matchPreferenceMapper.selectByUserId(userId);
        return preference == null ? toVO(defaultRow(userId), false) : toVO(preference, true);
    }

    @Override
    public PreferenceVO save(Long userId, PreferenceRequest request) {
        MatchPreference row = normalize(userId, request);
        MatchPreference existing = matchPreferenceMapper.selectByUserId(userId);
        if (existing == null) {
            try {
                matchPreferenceMapper.insert(row);
                return toVO(row, true);
            } catch (DuplicateKeyException e) {
                // 两个端同时第一次保存：uk_user 只留下一行，另一个转成按 id 更新那一行
                MatchPreference concurrent = matchPreferenceMapper.selectByUserId(userId);
                // 撞了唯一键却读不到行，只可能是对方在两次读之间又插又删；回一句「稍后重试」而不是
                // 把 null 拼进下面的 update（那会变成一次 no-op 而用户看到「保存成功」）
                BusinessException.throwIf(concurrent == null, ResultCode.SYSTEM_ERROR);
                row.setId(concurrent.getId());
                matchPreferenceMapper.updateById(row);
                return toVO(row, true);
            }
        }
        row.setId(existing.getId());
        matchPreferenceMapper.updateById(row);
        return toVO(row, true);
    }

    @Override
    public MatchPreference find(Long userId) {
        return userId == null ? null : matchPreferenceMapper.selectByUserId(userId);
    }

    @Override
    public void applyTo(MatchPreference preference, DatingCandidateQueryDTO query) {
        if (preference == null || query == null) {
            return;
        }
        if (query.getTargetGender() == null) {
            query.setTargetGender(zeroToNull(preference.getTargetGender()));
        }
        if (query.getAgeMin() == null) {
            query.setAgeMin(preference.getAgeMin());
        }
        if (query.getAgeMax() == null) {
            query.setAgeMax(preference.getAgeMax());
        }
        if (query.getHeightMin() == null) {
            query.setHeightMin(zeroToNull(preference.getHeightMin()));
        }
        if (query.getHeightMax() == null) {
            query.setHeightMax(zeroToNull(preference.getHeightMax()));
        }
        if (query.getMinEducation() == null) {
            query.setMinEducation(zeroToNull(preference.getTargetEducation()));
        }
        if (query.getTargetMarriage() == null) {
            query.setTargetMarriage(zeroToNull(preference.getTargetMarriage()));
        }
        if (query.getCity() == null || query.getCity().isBlank()) {
            query.setCity(preference.getTargetCity());
        }
        if (query.getOnlineFirst() == null) {
            query.setOnlineFirst(preference.isOnlineFirst());
        }
        if (query.getSortBy() == null) {
            query.setSortBy(preference.getSortBy());
        }
        // nearbyRadiusMeters 刻意不在这里填：偏好里存的是 km 而且「不限」为 0，
        // 而半径的单位、默认值与夹紧都归调用方与 im-user（见 NearbyServiceImpl）
    }

    /**
     * 校验 + 归一化成一行待写实体。
     *
     * <p>越界值由 {@code PreferenceRequest} 的注解拦下，这里只管<b>注解表达不了的组合</b>：
     * 区间上下限反了、身高填了一个合法区间外的数（0 与 100~250 之间是「既不是不限也不是合法身高」）、
     * 城市只有一个字。这些都报错而不是夹紧——一个静默被改过的筛选条件，用户下次打开弹层看到的
     * 会是自己没填过的数字。
     */
    private MatchPreference normalize(Long userId, PreferenceRequest request) {
        MatchPreference row = defaultRow(userId);
        row.setUserId(userId);
        row.setTargetGender(orDefault(request.getTargetGender(), MatchPreference.ANY));
        row.setAgeMin(orDefault(request.getAgeMin(), MatchPreference.AGE_DEFAULT_MIN));
        row.setAgeMax(orDefault(request.getAgeMax(), MatchPreference.AGE_DEFAULT_MAX));
        row.setDistanceKm(orDefault(request.getDistanceKm(), MatchPreference.ANY));
        row.setTargetCity(request.getTargetCity() == null ? "" : request.getTargetCity().trim());
        row.setHeightMin(orDefault(request.getHeightMin(), MatchPreference.ANY));
        row.setHeightMax(orDefault(request.getHeightMax(), MatchPreference.ANY));
        row.setTargetEducation(orDefault(request.getTargetEducation(), MatchPreference.ANY));
        row.setTargetMarriage(orDefault(request.getTargetMarriage(), MatchPreference.ANY));
        row.setOnlineFirst(orDefault(request.getOnlineFirst(), MatchPreference.FLAG_OFF));
        row.setExcludePassed(orDefault(request.getExcludePassed(), MatchPreference.FLAG_ON));

        BusinessException.throwIf(row.getAgeMin() > row.getAgeMax(),
                ResultCode.BAD_REQUEST, "年龄上限不能小于下限");
        BusinessException.throwIf(!isAnyOrInRange(row.getHeightMin()),
                ResultCode.BAD_REQUEST, "身高下限只能留空（不限）或填 "
                        + MatchPreference.HEIGHT_MIN_FLOOR + "-" + MatchPreference.HEIGHT_MAX_CEIL + " cm");
        BusinessException.throwIf(!isAnyOrInRange(row.getHeightMax()),
                ResultCode.BAD_REQUEST, "身高上限只能留空（不限）或填 "
                        + MatchPreference.HEIGHT_MIN_FLOOR + "-" + MatchPreference.HEIGHT_MAX_CEIL + " cm");
        BusinessException.throwIf(row.getHeightMin() > MatchPreference.ANY
                        && row.getHeightMax() > MatchPreference.ANY
                        && row.getHeightMin() > row.getHeightMax(),
                ResultCode.BAD_REQUEST, "身高上限不能小于下限");
        BusinessException.throwIf(row.getDistanceKm() > MatchPreference.DISTANCE_MAX_KM,
                ResultCode.BAD_REQUEST, "距离上限最大 " + MatchPreference.DISTANCE_MAX_KM + " km，更远请用城市筛选");
        BusinessException.throwIf(!row.getTargetCity().isEmpty()
                        && row.getTargetCity().length() < CITY_MIN_LENGTH,
                ResultCode.BAD_REQUEST, "城市名至少 " + CITY_MIN_LENGTH + " 个字，单个字会命中一大片不相干的城市");
        DatingSort sort = DatingSort.of(request.getSortBy());
        BusinessException.throwIf(request.getSortBy() != null && !request.getSortBy().isBlank() && sort == null,
                ResultCode.BAD_REQUEST, "sortBy 只能是 recommend / active / distance / hot / new");
        row.setSortBy((sort == null ? DatingSort.RECOMMEND : sort).getCode());
        return row;
    }

    /** 出厂默认行：只用于「还没保存过」时回给前端，以及作为保存时的兜底基底。 */
    private static MatchPreference defaultRow(Long userId) {
        MatchPreference row = new MatchPreference();
        row.setUserId(userId);
        row.setTargetGender(MatchPreference.ANY);
        row.setAgeMin(MatchPreference.AGE_DEFAULT_MIN);
        row.setAgeMax(MatchPreference.AGE_DEFAULT_MAX);
        row.setDistanceKm(MatchPreference.ANY);
        row.setTargetCity("");
        row.setHeightMin(MatchPreference.ANY);
        row.setHeightMax(MatchPreference.ANY);
        row.setTargetEducation(MatchPreference.ANY);
        row.setTargetMarriage(MatchPreference.ANY);
        row.setOnlineFirst(MatchPreference.FLAG_OFF);
        row.setSortBy(DatingSort.RECOMMEND.getCode());
        row.setExcludePassed(MatchPreference.FLAG_ON);
        return row;
    }

    private static PreferenceVO toVO(MatchPreference row, boolean saved) {
        DatingSort sort = DatingSort.of(row.getSortBy());
        return PreferenceVO.builder()
                .saved(saved)
                .targetGender(row.getTargetGender())
                .ageMin(row.getAgeMin())
                .ageMax(row.getAgeMax())
                .distanceKm(row.getDistanceKm())
                .targetCity(row.getTargetCity() == null ? "" : row.getTargetCity())
                .heightMin(row.getHeightMin())
                .heightMax(row.getHeightMax())
                .targetEducation(row.getTargetEducation())
                // 0（不限）给空串而不是「未填写」：这一条根本没有条件，chip 上不应当出现一句话
                .targetEducationDesc(educationDesc(row.getTargetEducation()))
                .targetMarriage(row.getTargetMarriage())
                .targetMarriageDesc(marriageDesc(row.getTargetMarriage()))
                .onlineFirst(row.getOnlineFirst())
                .sortBy((sort == null ? DatingSort.RECOMMEND : sort).getCode())
                .sortByDesc((sort == null ? DatingSort.RECOMMEND : sort).getDesc())
                .excludePassed(row.getExcludePassed())
                .build();
    }

    private static boolean isAnyOrInRange(Integer value) {
        return value == null || value == MatchPreference.ANY
                || (value >= MatchPreference.HEIGHT_MIN_FLOOR && value <= MatchPreference.HEIGHT_MAX_CEIL);
    }

    /** 不限（0 与脏值）回空串；descOf 自己就把 0 当成「未填」处理，这里只多挡一层 null。 */
    private static String educationDesc(Integer code) {
        return code == null || code <= MatchPreference.ANY ? "" : DatingEducation.descOf(code);
    }

    private static String marriageDesc(Integer code) {
        return code == null || code <= MatchPreference.ANY ? "" : DatingMarriage.descOf(code);
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /** 存的是 0（不限），摊给查询侧却要的是「不带这个条件」，null 才是那个表达。 */
    private static Integer zeroToNull(Integer value) {
        return value == null || Objects.equals(value, MatchPreference.ANY) ? null : value;
    }
}
