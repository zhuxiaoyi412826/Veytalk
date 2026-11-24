package com.im.user.service;

import com.im.user.dto.req.DatingPrivacyRequest;
import com.im.user.dto.req.DatingProfileRequest;
import com.im.user.dto.vo.DatingDictVO;
import com.im.user.dto.vo.DatingProfileVO;
import com.im.user.entity.UserProfile;

import java.util.Collection;
import java.util.Map;

/**
 * 交友资料卡（{@code im_user_profile}）读写。
 *
 * <p>归属 im-user：这张表是「用户的另一份档案」，与 {@code im_user} 同时被同一个人编辑；
 * 交友的<b>关系与行为</b>（配对、访客、亲密度、动态）才归 im-friend。
 */
public interface DatingProfileService {

    /**
     * 取本人资料，缺行时补一行全默认值。
     *
     * <p>只在「本人读写」路径上用：V1.0.7 已给存量用户回填过基础行，注册钩子却没建——
     * 读一个不存在就顺手建行是安全的（主键即 user_id，重复请求撞唯一主键会被吞掉），
     * 但<b>看别人</b>的资料时绝不能调它，否则刷 50 个人就凭空多 50 行。
     */
    UserProfile getOrCreate(Long userId);

    /** 只读取资料，缺行返回 {@code null}：他人视角与批量场景走这里。 */
    UserProfile find(Long userId);

    /** 批量只读取资料，返回 {@code userId -> profile}；没有行的用户不出现在结果里。 */
    Map<Long, UserProfile> findMap(Collection<Long> userIds);

    /** 本人视角：含隐私开关、完资料度与待补提示。 */
    DatingProfileVO mine(Long userId);

    /**
     * 他人视角：按对方的隐私开关收口（年龄、距离），且<b>不含任何精确坐标</b>。
     *
     * @param currentUserId 可为 null（内部调用），为 null 时不算距离
     */
    DatingProfileVO view(Long targetUserId, Long currentUserId);

    /** 保存交友资料（null 字段不修改，清空走 clears），返回最新资料。 */
    DatingProfileVO save(Long userId, DatingProfileRequest request);

    /** 保存隐私与可见性开关，返回最新资料。 */
    DatingProfileVO savePrivacy(Long userId, DatingPrivacyRequest request);

    /** 四个下拉字典，前端选择器的唯一出处。 */
    DatingDictVO dict();

    /**
     * 重算并落库完资料度，返回新值。
     *
     * <p>照片墙、兴趣标签保存后必须调它：分数是冗余列，不来这一次就停在旧值上，
     * 而用户看到的正是这个数字（推荐流与筛选按它排序）。
     */
    int recalcScore(Long userId);

    /**
     * 刷新活跃时间（滑动匹配、发消息等动作调用），供「活跃度优先」排序。
     *
     * <p>尽力而为：资料行不存在时静默跳过，不为了一个排序列去建行。
     */
    void touchActive(Long userId);
}
