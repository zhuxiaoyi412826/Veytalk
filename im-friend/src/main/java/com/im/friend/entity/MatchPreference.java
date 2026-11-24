package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 筛选与偏好（{@code im_match_preference}）：一人一行，描述「我想看到什么样的人」。
 *
 * <p>它与 {@code im_user_profile} 是两件事：后者是<b>我是谁</b>（别人按它筛我），前者是<b>我要筛谁</b>。
 * 混在一列里就会出现「改了筛选条件把自己的资料也改了」，而且隐私收口的对象完全不同。
 *
 * <p><b>这一行只住 im-friend，但它决定的 SQL 全在 im-user 那边执行</b>：{@code birthday} /
 * {@code height_cm} / {@code education} 这些列在 {@code im_user_profile} 里，星型架构下本模块读不到，
 * 所以服务层把这一行摊平成 {@code DatingCandidateQueryDTO} 的字段再交出去
 * （见 {@code MatchPreferenceServiceImpl#applyTo}）。
 *
 * <p>没有 {@code deleted} 列，因此继承 {@link AuditEntity} 而不是 {@code BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_match_preference")
public class MatchPreference extends AuditEntity {

    /** 年龄下限的硬地板：未成年人不进交友池，比这更小的入参直接报错而不是夹到 18 */
    public static final int AGE_FLOOR = 18;
    public static final int AGE_CEIL = 80;
    /** 出厂区间：够宽，不至于让一个从没打开过筛选弹层的用户把卡池筛空 */
    public static final int AGE_DEFAULT_MIN = 18;
    public static final int AGE_DEFAULT_MAX = 45;

    /** 距离上限的顶，与「附近的人」半径上限同源（20 km 以外属于同城筛选，不属「附近」） */
    public static final int DISTANCE_MAX_KM = 20;
    /** 身高合法区间，与 {@code DatingProfileRequest} 的资料校验一致 */
    public static final int HEIGHT_MIN_FLOOR = 100;
    public static final int HEIGHT_MAX_CEIL = 250;

    /** 性别 / 学历 / 婚恋状况里的「不限」，与各枚举自己的 {@code UNSET} 同值 */
    public static final int ANY = 0;

    public static final int FLAG_ON = 1;
    public static final int FLAG_OFF = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户，{@code uk_user} 保证一人一行 */
    private Long userId;

    /** 对方性别：0 不限 1 男 2 女 */
    private Integer targetGender;

    private Integer ageMin;
    private Integer ageMax;

    /** 距离上限 km：0 不限；只对「附近的人」那一栏生效（没有 geohash 扫描窗口就无从按距离筛） */
    private Integer distanceKm;

    /** 只看指定城市（包含匹配）：空 = 不限。与 {@code distanceKm} 是两条独立通道，同城筛选不依赖定位权限 */
    private String targetCity;

    /** 身高下限 cm：0 不限 */
    private Integer heightMin;
    /** 身高上限 cm：0 不限 */
    private Integer heightMax;

    /** 最低学历：0 不限，其余同 {@code im_user_profile.education}（学历码是升序的，所以按 &gt;= 比） */
    private Integer targetEducation;

    /** 婚恋状况：0 不限，其余同 {@code im_user_profile.marriage}（等值匹配） */
    private Integer targetMarriage;

    /** 在线优先：1 把在线的排前面（在线态来自 Redis，只能在内存里重排） */
    private Integer onlineFirst;

    /** 排序口径，取值见 {@code DatingSort} */
    private String sortBy;

    /** 是否排除已跳过的人：1 排除（与「不再出现」一致） 0 允许重新回到卡池 */
    private Integer excludePassed;

    public boolean isOnlineFirst() {
        return onlineFirst != null && onlineFirst == FLAG_ON;
    }

    /** 默认排除已跳过的人：这一行缺失（从没保存过偏好）时的行为要与第四批的卡池完全一致。 */
    public boolean isExcludePassed() {
        return excludePassed == null || excludePassed == FLAG_ON;
    }

    /** 偏好里的距离换算成米；没设或为 0 时回 {@code fallback}，让调用方决定默认半径。 */
    public int radiusMetersOr(int fallback) {
        return distanceKm == null || distanceKm <= ANY ? fallback : distanceKm * 1000;
    }
}
