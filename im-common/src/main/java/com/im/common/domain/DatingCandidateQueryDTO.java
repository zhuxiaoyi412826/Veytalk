package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;

/**
 * 交友候选池查询入参（跨模块）。
 *
 * <p>做成对象而不是三个平铺参数：这批功能里「附近的人」（按 geohash 圈）、「筛选与偏好」
 * （按年龄/身高/城市）、「推荐流」（按热度与曝光池）都要往这里加条件，
 * 平铺参数每加一项就得改一次接口签名，把 im-user 与 im-friend 两边一起动一遍。
 *
 * <p><b>排除名单由调用方给</b>：已划过、已配对、已拉黑这些行全部住在 im-friend 的表里，
 * im-user 读不到。数据归属谁，SQL 就写在谁那里——所以这里只传 ID，不传「为什么排除」。
 *
 * <p><b>同理不传经纬度</b>：{@link #nearbyRadiusMeters} 只说「圈多大」，
 * 查看者自己在哪、geohash 取几位、误差怎么算全归 im-user（坐标一出 im-user 就会开始
 * 在模块间漂，而防跟踪靠的正是「坐标只在所有者手里算一句模糊文案」）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友候选池查询参数")
public class DatingCandidateQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "查看者用户 ID，必填：候选池要按 TA 的隐私与定位收口")
    private Long viewerId;

    @Schema(description = "要排除的用户 ID（已划过 / 已配对 / 已拉黑等），可为空")
    private Collection<Long> excludeIds;

    @Schema(description = "期望返回条数，实现方会夹到 [1, 50]")
    private Integer limit;

    /*
     * 下面两个 ID 集合是「推荐流与排序」的接口：热度与曝光记录都住在 im-friend 的
     * {@code im_match_like} / {@code im_match_exposure} 里，im-user 读不到，所以它们只能
     * 以「一批 ID」的形式进来——与 {@link #excludeIds} 同构，也只是把「谁」传过去，
     * 「排到第几位」仍由 im-user 定（稳定分区，不打分）。
     */

    /**
     * 本次要置顶的一批人（推荐流的<b>互惠度</b>：已经喜欢过我的人）。
     *
     * <p>排在最前面，<b>优先级高于 {@code onlineFirst}</b>：一个不会回应的在线陌生人，
     * 不如一个现在就离线的、已经喜欢过我的人值得先看。
     */
    @Schema(description = "置顶的用户 ID（互惠：喜欢过我的人），可为空")
    private Collection<Long> boostIds;

    /**
     * 本次要垫到最后面的一批人（推荐流的<b>曝光池</b>：最近已经推过的人）。
     *
     * <p>刻意不是「再排一次」而是「垫后」：同一个人被推第三遍仍然可能成对，直接排除
     * 会把卡池抽干（尤其是小池子里只有一二十人时），而垫后就只在真的没新面孔时才会看到。
     */
    @Schema(description = "垫后的用户 ID（最近已曝光过的人），可为空")
    private Collection<Long> deprioritizeIds;

    /**
     * 是否把低质号（{@code im_user_profile.quality_flag=1}）直接挡在池外。
     *
     * <p><b>「自动降权」在这一个开关上落地为「不进推荐流」而不是「分数排到末尾」</b>：卡池一次
     * 只取十几条、扫描窗口上限 50 行，把低质号排到 50 名以后与不让它出现是同一件事，
     * 而写成显式排除能解释、能测试。被降权的人仍能从「喜欢我的」「附近的人」与搜索里被看到。
     */
    @Schema(description = "true = 低质号不进候选池；null / false = 不区分（默认，保持原有行为）")
    private Boolean excludeLowQuality;

    /**
     * 附近的人半径（米）。实现方会夹到 [500, 20000]：比 500 m 更小就是在筛同一栋楼，
     * 比 20 km 更大则「附近」已经名不副实（那一档属于「筛选与偏好」按 city 包含匹配）。
     */
    @Schema(description = "附近的人半径（米）；非 null 就按查看者自己的定位圈人并按距离升序，null = 推荐流口径")
    private Integer nearbyRadiusMeters;

    /*
     * 以下都是「筛选与偏好」（{@code im_match_preference}）摊开后的条件，由 im-friend 组装：
     * 那一行住在 im-friend，而 birthday / height_cm / education 这些列住在 im_user_profile，
     * 星型架构下只能把「要什么」传过去、让 im-user 决定怎么用索引。
     *
     * <p><b>统一约定：null 与 0 都是「不限」</b>，实现方按「不拼这一条」处理。年龄是唯一例外：
     * 它要转成出生日期区间才能进 SQL（年龄不落库），而「不限年龄」也只能表达成 18~80
     * （未成年人不进交友池），所以 {@code ageMin}/{@code ageMax} 为 null 时实现方不拼条件。
     */

    @Schema(description = "对方性别：null / 0 / 1 以外的脏值 = 不限，1 男 2 女（这一列在 im_user，不在资料表）")
    private Integer targetGender;

    @Schema(description = "对方年龄下限（含），null 不限")
    private Integer ageMin;

    @Schema(description = "对方年龄上限（含），null 不限")
    private Integer ageMax;

    @Schema(description = "对方身高下限 cm，null / 0 不限")
    private Integer heightMin;

    @Schema(description = "对方身高上限 cm，null / 0 不限")
    private Integer heightMax;

    @Schema(description = "最低学历（按 {@code DatingEducation} 的 code 比大小，学历码是升序设计的）；null / 0 不限")
    private Integer minEducation;

    @Schema(description = "婚恋状况（等值匹配，见 {@code DatingMarriage}）；null / 0 不限")
    private Integer targetMarriage;

    /**
     * 只看指定城市。资料里的城市是自由文本，所以按「包含」而不是等值匹配：
     * 等值会让「杭州」与「杭州市」互不可见，那是一种用户看不出原因的空池。
     */
    @Schema(description = "只看指定城市（包含匹配）；null / 空串不限")
    private String city;

    @Schema(description = "在线优先：把在线的人排在前面（在线态来自 Redis，只能在取回一批后在内存里重排）")
    private Boolean onlineFirst;

    /**
     * 排序口径，取值为 {@code DatingSort} 的 code。实现方收到不认识的值按 {@code recommend} 处理，
     * 不报错：这一列是偏好而不是指令，一个写坏的字符串不该让整个卡池空掉。
     *
     * <p>五个取值都已在「推荐流与排序」那批生效，但实现位置不同：{@code active} / {@code new}
     * 是一句 ORDER BY，{@code hot} 需要「近几天活跃」这个内存分区（它的另一半天分
     * ——收到多少喜欢——在 im-friend，只能靠 {@link #boostIds} 送进来）。
     */
    @Schema(description = "排序口径：recommend / active / distance / hot / new；null 按 recommend")
    private String sortBy;
}
