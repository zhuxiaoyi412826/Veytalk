package com.im.user.service;

import com.im.user.dto.req.TagSaveRequest;
import com.im.user.dto.vo.TagCategoryVO;
import com.im.user.dto.vo.TagVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 兴趣标签体系（{@code im_tag} 标签库 + {@code im_user_tag} 用户自选）。
 *
 * <p>归 im-user：标签是「这个人是什么」的一部分，与 {@code im_user_profile} 同一侧；
 * 「谁和谁同好」那种反查才归 im-friend（滑动匹配、附近的人）。
 *
 * <p>标签用<b>字典 + 关联表</b>而不是资料上一个逗号串，是为了能被反查与改名：
 * 逗号串只能全表 {@code LIKE}，而「健身→运动健身」这种改名要刷穿全表。
 */
public interface DatingTagService {

    /**
     * 标签库：只含启用中的标签，按分类分组。
     *
     * <p>分类顺序取「该分类最小的 sort」，即运营在标签库里排出来的顺序；停用的标签不出现在这里，
     * 但已经绑定在它上面的用户标签<b>保留</b>（下架一个标签不该顺手删掉几百个人资料卡上的一栏）。
     */
    List<TagCategoryVO> library();

    /** 某个用户已选的标签，按其自选顺序（最重要的兴趣在前）；本人和别人看到的是同一份。 */
    List<TagVO> listByUser(Long targetUserId);

    /**
     * 批量取标签<b>名字</b>（按自选顺序），给交友卡池与列表页用。
     *
     * <p>与 {@link #listByUser} 分开而不是复用：卡池一页十几个人，逐个调就是十几条 SQL；
     * 而列表场景只要一个名字去渲 chip，把 tagId / useCount / disabled 全带上只是把无用的字段带到跨模块。
     *
     * @return key 为 userId，<b>一个标签都没选的用户不出现在结果里</b>，调用方按空处理
     */
    Map<Long, List<String>> namesByUsers(Collection<Long> userIds);

    /**
     * 整组替换本人的标签，并返回替换后的结果。
     *
     * <p>会同步增减 {@code im_tag.use_count} 与重算完资料度——标签那一档占 15 分。
     */
    List<TagVO> save(Long userId, TagSaveRequest request);
}
