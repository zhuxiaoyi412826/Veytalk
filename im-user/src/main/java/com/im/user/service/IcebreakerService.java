package com.im.user.service;

import com.im.user.dto.vo.IcebreakerSuggestVO;

/**
 * 破冰文案（{@code im_icebreaker}）的下发与使用计数。
 *
 * <p>归属 im-user 的理由与标签库一致：三档文案里有一档要按<b>双方共有的兴趣标签</b>挑，
 * 而标签库与绑定行都在本模块（见 {@code Icebreaker} 的类注释）。
 */
public interface IcebreakerService {

    /**
     * 给「和这个人聊什么」的三档建议。
     *
     * <p>占位符在本方法里替换完毕，返回的就是成品文案。
     *
     * @param currentUserId 当前登录人，用于算共同标签与「不要给自己推荐」
     * @param targetUserId  建议针对的人；为 null 或等于本人时只给通用文案（{@code commonTags} 与
     *                      {@code tagOpeners} 为空数组）
     */
    IcebreakerSuggestVO suggest(Long currentUserId, Long targetUserId);

    /**
     * 记一次「用户点了这条」，供运营淘汰没人用的文案。
     *
     * @throws com.im.common.exception.BusinessException 这条已停用或已删除
     */
    void use(Long id);
}
