package com.im.user.service;

import com.im.user.dto.req.PhotoAddRequest;
import com.im.user.dto.req.PhotoSortRequest;
import com.im.user.dto.vo.UserPhotoVO;
import com.im.user.entity.UserPhoto;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 照片墙（{@code im_user_photo}）读写。
 *
 * <p>归属 im-user：照片是「这个人的档案内容」，和 {@code im_user_profile} 同一侧；
 * 而「谁给谁点了喜欢」那类关系与行为才归 im-friend。
 *
 * <p>本接口是照片可见性的<b>唯一</b>收口点：{@code dating_photo} 的文件下载地址本身是公开的
 * （陌生人的资料卡要能渲染），所以「哪一张能被看到」只能在这里按 {@code audit_status} 决定。
 */
public interface DatingPhotoService {

    /**
     * 列表：本人视角返回全部（含待审与驳回，带状态与原因），他人视角<b>只返回审核通过的行</b>。
     *
     * @param targetUserId  照片归属人
     * @param currentUserId 访问者；等于归属人时为本视角
     */
    List<UserPhotoVO> list(Long targetUserId, Long currentUserId);

    /**
     * 新增一张：校验文件归属与业务类型、张数上限、同图重复，落库后按需要重排封面位。
     *
     * @return 新增后的本人视角列表（前端可直接整块重绘，省一次回读）
     */
    List<UserPhotoVO> add(Long userId, PhotoAddRequest request);

    /**
     * 删除一张：只逻辑删照片行，<b>不销毁文件记录</b>——相同 MD5 秒传时这一条
     * {@code im_file} 可能还挂着别人的头像或聊天图，删了记录会把他们的下载一并打死。
     */
    List<UserPhotoVO> delete(Long userId, Long photoId);

    /** 设为封面：把它挪到第一位，其余顺序相对不变。 */
    List<UserPhotoVO> setMain(Long userId, Long photoId);

    /** 按提交的完整顺序重排，第一个成为封面。 */
    List<UserPhotoVO> sort(Long userId, PhotoSortRequest request);

    /**
     * 取资料卡与匹配卡片该展示的那一张封面。
     *
     * <p>规则是「主封面且已审核通过 → 否则第一张已通过的 → 否则 null」，而不是直接取
     * {@code is_main=1}：用户完全可能把一张待审照片拖到第一位，那时对外得自动退回第二张，
     * 否则资料卡就成了一片空白，而他本人页面上明明写着「已设为封面」。
     */
    UserPhotoVO cover(Long userId);

    /**
     * 批量取封面：与 {@link #cover} <b>同一规则</b>（主封面且已过审 → 否则第一张已过审的），
     * 只是不逐个查一次。
     *
     * <p>存在这个接口上而不是让调用方自己拼 SQL：「哪一张能被看到」是本接口的唯一收口点，
     * 卡池、附近的人、动态列表各自过滤一遍「审核通过」就会各自漂移，
     * 而漂移的结果是把待审照片发给了陌生人。
     *
     * @return key 为 userId，<b>一张能被看的都没有的用户不出现在结果里</b>
     */
    Map<Long, UserPhotoVO> covers(Collection<Long> userIds);

    /** 批量取通过张数，供列表页渲染；没有通过照片的用户不出现在结果里。 */
    Map<Long, Integer> countApprovedByUserIds(Collection<Long> userIds);

    /** 原始实体列表，只给服务层内部与后续功能用（不对外）。 */
    List<UserPhoto> listEntities(Long userId);
}
