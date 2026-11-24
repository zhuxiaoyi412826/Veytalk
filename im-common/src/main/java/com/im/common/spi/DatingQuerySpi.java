package com.im.common.spi;

import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.common.domain.DatingCardDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 交友资料查询契约，由 im-user 模块实现，供 im-friend 的匹配、访客、动态等功能读。
 *
 * <p>为什么不复用 {@link UserQuerySpi}：那个契约给的是「一个人对所有人可见的身份」
 * （昵称、头像、在线），而交友卡片要带年龄、身高、封面、距离这些<b>要按对方隐私开关收口</b>的字段。
 * 混进去等于把交友的隐私规则塞进全站都在用的用户查询里，改一次影响所有页面。
 *
 * <p>为什么不在 im-friend 里直接查那三张表：{@code im_user_profile} / {@code im_user_photo} /
 * {@code im_user_tag} 归 im-user，星型架构下跨模块只能走契约。收口点也必须只有一个——
 * 年龄隐藏与距离模糊化的规则一旦复制到两个模块，就会有两套口径，而以「谁能看到多少」的形式暴露出来。
 */
public interface DatingQuerySpi {

    /**
     * 取候选池的用户 ID（滑动匹配、附近的人与推荐流共用这一条读法）。
     *
     * <p>只返回「可被发现（{@code discoverable=1}）、不是查看者本人、不在排除名单里」的人，
     * 按完资料度倒序、同分按最近活跃倒序。<b>排序口径是本方法的唯一职责</b>：
     * 调用方拿到的就是顺序，不要再在自己那边排一遍（热度、新鲜度那套加权属于「推荐流」功能，
     * 到时候在这里换实现，而不是让每个调用方各排一次）。
     *
     * <p>给了 {@code nearbyRadiusMeters} 就换成「附近的人」口径：<b>按距查看者由近到远排序</b>，
     * 且查看者自己定位失效时返回空池（不报错，错误码由调用方用 {@link #locationReady} 自己决定）。
     *
     * <p>返回的条数可能少于 {@code limit}：候选还要过一道账号状态检查（被禁用的账号不发卡）。
     *
     * @return 用户 ID，按应展示的顺序；无候选人时返回空集合，<b>不返回 null</b>
     */
    List<Long> candidateIds(DatingCandidateQueryDTO query);

    /**
     * 查看者自己现在能不能按距离找人：定位存在、坐标合法且在采信期内。
     *
     * <p>单独开一个方法而不是让 {@code candidateIds} 区分两种空：「你没定位」是一个
     * 能一句提示引导用户去修的状态，「附近没人」只能让他换个半径再试，
     * 把两者合成一个空集合就只能对用户说两句都对不上的一句话。
     */
    boolean locationReady(Long userId);

    /**
     * 批量取交友卡片，一次查完，调用方不要循环单个取。
     *
     * <p>卡片按 {@code viewerId} 的视角收口（年龄、距离、在线态）。<b>没有交友资料行的用户也会给一张卡</b>
     * （只有昵称、头像与在线态），因为「TA 注册后还没进过交友页」是正常状态，不是错误；
     * 返回的顺序不保证，调用方按自己那侧的顺序取。
     *
     * @param viewerId  查看者；为 null 时不算距离（内部任务用）
     * @return key 为 userId，被删除的用户不出现在结果里
     */
    Map<Long, DatingCardDTO> cards(Collection<Long> userIds, Long viewerId);

    /**
     * 这个人当前能不能被 TA 发现：账号正常、开了 {@code discoverable}、且不是本人。
     *
     * <p>滑动「喜欢」一个不在卡池里的人（前端缓存的旧卡片、或直接构造请求）时用它兜底，
     * 免得隐身成了一句只约束推荐流、不约束写侧的空话。
     */
    boolean discoverable(Long userId, Long viewerId);

    /**
     * 刷一次活跃时间（{@code im_user_profile.last_active_time}）。
     *
     * <p>尽力而为：资料行不存在时静默跳过。「活跃度优先」的排序与推荐流都取这一列，
     * 所以滑动、打招呼这类动作都要顺手刷一下，否则三天没动的活跃用户会一路沉底。
     */
    void touchActive(Long userId);

    /**
     * 批量取「动态几天可见」偏好（{@code im_user_profile.moment_visible_days}）。
     *
     * <p>动态流归 im-friend，而这一列在 im-user 的资料表上，所以只能经本契约取。
     * 它是<b>作者对全部历史动态的时间口径</b>，与单条的 {@code visibility} 是两件事，
     * 两者都要满足才看得到（否则「设了三天可见」会被一条选了公开的动态绕过）。
     *
     * <p>没有资料行的人<b>不出现在结果里</b>：调用方对缺失的 key 一律按最严的一档处理
     * （见 {@code MomentVisibleDays#of}），隐私位宁可少给。
     *
     * @return key 为 userId，value 为 0/3/7/30；入参为空时返回空 Map，<b>不返回 null</b>
     */
    Map<Long, Integer> momentVisibleDays(Collection<Long> userIds);
}
