package com.im.friend.service;

import com.im.friend.dto.vo.IntimacyVO;

/**
 * 亲密度与火花（{@code im_intimacy}）。
 *
 * <p>写入与读取都在本模块：这张表归 im-friend，产生分的动作却分散在 im-message（聊天）
 * 与将来的动态点赞评论，跨模块那一侧只经 {@code IntimacySpi} 递事实进来。
 *
 * <p>「加多少分 / 火花怎么算 / 档位在哪」三个口径分别落在
 * {@code Intimacy} 的分值常量、{@code IntimacyMapper} 的 upsert CASE、{@code IntimacyLevel} 的档位边界，
 * 本接口只负责把一次动作翻译成对这三处的一次调用，不再重复定义任何数值。
 */
public interface IntimacyService {

    /**
     * 看我与某个人的亲密度、火花、共同好友与共同群。
     *
     * <p>从没来往过、或对方不存在时<b>不返回 null</b>，而是返回一个全 0 的 {@link IntimacyVO}：
     * 0 分 0 天本身就是准确事实，前端因此不需要多一条「这块要不要渲染」的分支。
     *
     * @param currentUserId 当前登录人；为 null 时返回全 0 兜底
     * @param targetUserId  对方；为 null 或与当前人相同（看自己）时同样返回全 0 兜底
     */
    IntimacyVO view(Long currentUserId, Long targetUserId);

    /**
     * 一条单聊消息落库：消息数 +1、加分、按自然日推进或重置火花。
     *
     * <p>只认<b>单聊</b>，且发送方与接收方必须是两个不同的真实用户——
     * 系统通知、机器人消息、群消息都不该走到这里，判定由调用方负责（口径见 {@code IntimacySpi}）。
     *
     * <p>失败向上抛：本方法处在消息发送事务提交之后，抛出的异常由 SPI 实现方吞掉并记日志，
     * 在服务层就吞掉会让「计数表坏了」这件事在日志里完全看不见。
     */
    void addChatMessage(Long fromUserId, Long toUserId);

    /**
     * 一次动态互动（点赞 / 评论）：互动数 +1、加分，<b>不推进火花</b>。
     *
     * @param scoreDelta 这一次加的分，取 {@code Intimacy} 的 {@code SCORE_PER_MOMENT_*} 常量
     */
    void addInteract(Long actorId, Long authorId, int scoreDelta);
}
