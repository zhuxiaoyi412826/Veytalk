package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台用的交友档案，由 im-user 经 {@code AdminDatingSpi} 提供。
 *
 * <p>与用户端的 {@code DatingCardDTO} 必须是两个类：卡片是<b>隐私收口后</b>的产物
 * （年龄可能被对方开关关掉、距离只给模糊档位、坐标绝不下发），而后台要看到原始值才能处理申诉。
 * 复用同一个 DTO 的后果是要么后台看不见真数据，要么卡片里多出经纬度——后一种已经在
 * {@code DatingQuerySpi} 的注释里被我明确禁过。
 *
 * <p>{@link #birthday} 给原始日期而不给算好的年龄：年龄随当天变化，在服务端算完传过去，
 * 到后台展示时可能已经跨了一天。前端要显年龄就自己算，实在不行留空。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminProfileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID（= {@code im_user.id}，也是 {@code im_user_profile} 主键） */
    private Long userId;

    /** 昵称（查不到账号时实现方给「已注销用户」占位，不留 null） */
    private String nickname;

    /** 登录名，比昵称更适合按它搜人 */
    private String username;

    /** 头像受控地址，可为空 */
    private String avatar;

    /** 账号状态：1 正常 0 已封禁（后台要把「资料正常但账号被封」区分开） */
    private Integer userStatus;

    private Integer gender;

    private LocalDate birthday;

    private Integer heightCm;

    /** 学历 code，文案由前端按枚举映射，后台不在跨模块 DTO 里塞中文 */
    private Integer education;

    private String occupation;

    private Integer income;

    private Integer marriage;

    private Integer datingGoal;

    private String province;

    private String city;

    private String district;

    /** 是否有仍在采信期内的定位（{@code location_time} 未过期且坐标合法） */
    private Boolean hasLocation;

    /** 动态可见范围偏好：0 全部 3 三天 7 一周 30 一个月 */
    private Integer momentVisibleDays;

    /** 可被发现：1 是 0 隐身（用户自设与后台强制落同一列，不另设「强制」列） */
    private Integer discoverable;

    /** 完资料度 0~100 */
    private Integer profileScore;

    /** 推荐质量标记：0 正常 1 低质降权 2 已升权 */
    private Integer qualityFlag;

    /** 已审核通过的照片张数（不是全部张数：待审与驳回的张数由照片那一屏给） */
    private Integer photoCount;

    /** 待人工复审的照片张数，列表上拿它提示「这个人有图要看」 */
    private Integer pendingPhotoCount;

    /** 已选兴趣标签名，最多给若干个，全量在详情里 */
    private List<String> tagNames;

    private LocalDateTime lastActiveTime;

    private LocalDateTime createTime;
}
