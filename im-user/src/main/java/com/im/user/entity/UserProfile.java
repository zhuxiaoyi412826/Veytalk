package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import com.im.common.util.GeoUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交友资料卡扩展，对应表 {@code im_user_profile}（1:1 挂 {@code im_user}）。
 *
 * <p>主键就是 {@code user_id}，且 {@link IdType#INPUT}：这是「一个人的另一份档案」而不是
 * 一堆可多条并存的记录，多一个雪花代理键只会让每次 join 多绕一步。也正因为它不是自增/雪花，
 * 插入前必须由服务层把 {@code userId} 塞进去，否则 MP 会拿 null 主键去 insert。
 *
 * <p>{@code profileScore} 与 {@code qualityFlag} 是<b>冗余计算值</b>，由
 * {@code DatingProfileService} 在保存资料/照片/标签后重算写入，不要手改：
 * 推荐流与筛选要按它们排序过滤，实时算就得把标签数、照片数、字段填充度全 join 一遍。
 *
 * <p>{@code lng}/{@code lat} 全精度存、只在服务端使用；对外一律先算距离再走
 * {@code GeoUtil.fuzzyDistance} 收口。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user_profile")
public class UserProfile extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 资料可见位：显示 */
    public static final int SHOW = 1;
    /** 资料可见位：隐藏 */
    public static final int HIDE = 0;

    /** 定位写入时间超过这个天数就不再采用：旧坐标会把人说成在别处 */
    public static final int LOCATION_VALID_DAYS = 7;

    /** 推荐质量标记：正常 */
    public static final int QUALITY_NORMAL = 0;
    /** 推荐质量标记：低质，服务端自动落，不进推荐流 */
    public static final int QUALITY_LOW = 1;
    /** 推荐质量标记：已升权，<b>只由后台真人认证写</b>，服务端任何重算都不会自动置到这个值 */
    public static final int QUALITY_BOOSTED = 2;

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 出生日期；年龄按当天现算，不落冗余年龄列 */
    private LocalDate birthday;

    /** 身高 cm；合法区间 100~250 在 {@code DatingProfileRequest} 的注解上校验，不在这里再存一份 */
    private Integer heightCm;

    /** 学历 code，见 {@code DatingEducation}，0 未填 */
    private Integer education;

    private String occupation;

    /** 收入区间 code，见 {@code DatingIncome}，0 未填 */
    private Integer income;

    /** 婚恋状况 code，见 {@code DatingMarriage}，0 未填 */
    private Integer marriage;

    /** 交友目的 code，见 {@code DatingGoal}，0 未填 */
    private Integer datingGoal;

    private String province;

    /** 所在城市，精确到市；同城筛选按它做包含匹配（自由文本，等值会让「杭州」与「杭州市」互不可见） */
    private String city;

    /** 区县，只参与展示，不参与任何计算 */
    private String district;

    private BigDecimal lng;

    private BigDecimal lat;

    /** 由 {@code lng/lat} 服务端算出的 6 位 geohash，附近的人按前缀圈候选池 */
    private String geohash;

    private LocalDateTime locationTime;

    /** 动态可见范围偏好：0 全部 3 三天 7 一周 30 一个月 */
    private Integer momentVisibleDays;

    private Integer showAge;

    private Integer showDistance;

    /** 是否对陌生人显示在线状态；好友与已配对不受它影响 */
    private Integer showOnline;

    private Integer discoverable;

    /** 完资料度 0~100 */
    private Integer profileScore;

    /** 最近活跃时间（保存资料、滑动、发消息时刷新），「活跃度优先」与「热门」排序取它 */
    private LocalDateTime lastActiveTime;

    /** 推荐质量标记，见 {@link #QUALITY_NORMAL} 三个常量；与 {@code profileScore} 同一次重算写入 */
    private Integer qualityFlag;

    /** 定位是否仍在采信期内（{@code location_time} 未超 {@link #LOCATION_VALID_DAYS} 天且坐标合法）。 */
    public boolean hasFreshLocation() {
        if (locationTime == null || lng == null || lat == null) {
            return false;
        }
        return locationTime.isAfter(LocalDateTime.now().minusDays(LOCATION_VALID_DAYS));
    }

    /**
     * 城市级展示文本，最多到区：省一栏留给筛选与后台统计，资料卡上一行塞不下三段。
     *
     * <p>放在实体上而不是服务层：资料卡、交友卡池、动态列表三处都要拼这一句，
     * 写三份就会出现「同一个人在自己资料页与卡池里城市写法不一致」。
     */
    public String locationText() {
        String cityText = city == null || city.isBlank() ? "" : city.trim();
        String districtText = district == null || district.isBlank() ? "" : district.trim();
        if (cityText.isEmpty() && districtText.isEmpty()) {
            return "";
        }
        return districtText.isEmpty() ? cityText : (cityText + " · " + districtText);
    }

    /** 年龄能不能给外人看。列为 NULL 按「不显示」：隐私位宁可少给，不可多给。 */
    public boolean ageVisible() {
        return isOn(showAge);
    }

    /** 距离能不能给外人看，口径同 {@link #ageVisible()}。 */
    public boolean distanceVisible() {
        return isOn(showDistance);
    }

    /**
     * 在线状态能不能给陌生人看。
     *
     * <p>与上面两个隐私位相反，<b>列为 NULL 按「显示」</b>：没写过就是没主动关过，
     * 把缺行当成隐身会让一个从没进过交友页的账号在所有人眼里都是离线。
     */
    public boolean onlineVisibleToStranger() {
        return showOnline == null || showOnline == SHOW;
    }

    /**
     * 是否隐身（{@code discoverable = 0}）：不被陌生人刷到，也不留下自己的足迹。
     *
     * <p>与 {@link #onlineVisibleToStranger()} 相反，<b>列为 NULL 按「隐身」</b>：这一位管的是
     * 「要不要被陌生人发现」，宁可少给曝光不可多给。DDL 上它是 {@code NOT NULL DEFAULT 1}，
     * 所以真从库里读出的行不会是 NULL；留着这个口径是为了 {@code getOrCreate} 返回时
     * 那个还没落库的内存对象。
     *
     * <p><b>内存里判隐身只读它</b>：{@code DatingQuerySpiImpl#discoverable}（这个人能不能被对方刷到）
     * 与「该不该记访客足迹」两处都靠它。候选池的 WHERE 用的是同一个 {@link #SHOW} 常量
     * （{@code LambdaQueryWrapper} 里没法调实例方法），拆开写就会出现
     * 「隐身的人不出现在卡池，但看人还是留了痕」——后者正好把隐身废掉。
     */
    public boolean stealth() {
        return !isOn(discoverable);
    }

    /**
     * 与查看者之间的模糊距离文案；任一方无有效定位、或本行关了距离显示时返回 {@code null}。
     *
     * <p>只回文案不回坐标：精确坐标一经下发就收不回来，而防跟踪靠的正是这一句模糊。
     */
    public String distanceTextFrom(UserProfile viewer) {
        if (viewer == null || !distanceVisible() || !viewer.hasFreshLocation() || !hasFreshLocation()) {
            return null;
        }
        return GeoUtil.fuzzyDistanceBetween(toDouble(viewer.getLng()), toDouble(viewer.getLat()),
                toDouble(lng), toDouble(lat));
    }

    private static boolean isOn(Integer flag) {
        return flag != null && flag == SHOW;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
