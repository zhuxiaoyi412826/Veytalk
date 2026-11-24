package com.im.friend.spi;

import com.im.common.spi.IntimacySpi;
import com.im.friend.entity.Intimacy;
import com.im.friend.service.IntimacyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link IntimacySpi} 的实现：把别的模块递进来的「发生了一次互动」落进 {@code im_intimacy}。
 *
 * <p>★ 三个方法<b>一律吞异常只记日志</b>，这是契约里写死的（见 {@code IntimacySpi} 类注释）：
 * 亲密度是一个展示用的数字，让它把一条已经落库的消息发送打回失败，
 * 用户看到的是「消息发不出去」，而真正的原因是一张跟发消息无关的计数表——代价不对等。
 *
 * <p>日志用 {@code warn} 而不是 {@code error}：计数丢了不影响任何一条业务链路，
 * 挂进 error 会让告警通道被一个「不修也不会坏」的噪声占满；但也不能不打，
 * 否则这张表悄悄停止累计几周都不会有人发现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntimacySpiImpl implements IntimacySpi {

    private final IntimacyService intimacyService;

    @Override
    public void onChatMessage(Long fromUserId, Long toUserId) {
        try {
            intimacyService.addChatMessage(fromUserId, toUserId);
        } catch (Exception e) {
            log.warn("亲密度累计失败（单聊消息）from={} to={}: {}", fromUserId, toUserId, e.getMessage());
        }
    }

    @Override
    public void onMomentLike(Long likerId, Long authorId) {
        interact(likerId, authorId, Intimacy.SCORE_PER_MOMENT_LIKE, "点赞");
    }

    @Override
    public void onMomentComment(Long commenterId, Long authorId) {
        interact(commenterId, authorId, Intimacy.SCORE_PER_MOMENT_COMMENT, "评论");
    }

    /** 点赞与评论只差一个分值常量，异常处理与日志格式完全一样，合并成一个入口。 */
    private void interact(Long actorId, Long authorId, int scoreDelta, String action) {
        try {
            intimacyService.addInteract(actorId, authorId, scoreDelta);
        } catch (Exception e) {
            log.warn("亲密度累计失败（动态{}）actor={} author={}: {}", action, actorId, authorId, e.getMessage());
        }
    }
}
