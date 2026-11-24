package com.im.common.spi;

/**
 * 亲密度与火花（{@code im_intimacy}）的写入契约，由 im-friend 实现。
 *
 * <p>为什么要有这个 SPI：亲密度归 im-friend（它是「关系」而不是「资料」），
 * 而<b>产生亲密度的动作分散在别的模块</b>——聊天在 im-message，动态点赞评论在 im-friend 自己。
 * im-message 不能直接写 im-friend 的表（星型架构的铁律），所以由这条契约把「发生了一次互动」
 * 这个事实递过去，加多少分、火花怎么算全部留在 im-friend。
 *
 * <p>★ 三个方法都是<b>尽力而为</b>：实现方内部失败只记日志，不向调用方抛异常。
 * 亲密度是一个展示用的数字，让它把一条已经落库的消息发送打回失败，
 * 用户看到的是「消息发不出去」，而真正的原因是一个跟发消息无关的计数表——
 * 这个代价无论如何不对等。调用方也因此不需要包 try/catch。
 *
 * <p>调用方仍然要用 {@code ObjectProvider} 弱依赖注入：im-friend 与 im-message 之间
 * 已经有别的依赖路径，构造期硬等对方会拖出循环依赖，启动直接失败。
 */
public interface IntimacySpi {

    /**
     * 一条单聊消息落库后调用：累计消息数、加互动分，并按自然日推进/重置火花。
     *
     * <p>只有<b>单聊</b>该调它：群消息没有「一对人」这个主语，
     * 把群里的一句话算成两个人的亲密度，等于让一个 500 人群里的两个人自动变知己。
     *
     * <p>系统通知与机器人消息也不该调：它们不是这两个人之间发生的来往
     * （判定与拦截由调用方负责，实现方只认收到的两个 ID）。
     *
     * @param fromUserId 发送方
     * @param toUserId   接收方；实现方内部排序后写入，调用方不需要保证大小顺序
     */
    void onChatMessage(Long fromUserId, Long toUserId);

    /**
     * 给别人的动态点了一次赞。
     *
     * <p>加分但<b>不推进火花</b>：火花问的是「你们连续几天说过话」，
     * 一个赞没有说任何话。把它算进 streak 会让「每天给同一个人点赞」刷出一个假的 30 天。
     *
     * @param likerId  点赞的人
     * @param authorId 动态作者
     */
    void onMomentLike(Long likerId, Long authorId);

    /**
     * 评论了别人的动态（含回复作者的那一层）。
     *
     * <p>同样不推进火花，理由见 {@link #onMomentLike}。
     *
     * @param commenterId 评论的人
     * @param authorId    动态作者
     */
    void onMomentComment(Long commenterId, Long authorId);
}
