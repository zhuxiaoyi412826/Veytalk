package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminDatingQueryDTO;
import com.im.common.domain.AdminPhotoDTO;
import com.im.common.domain.AdminPhotoQueryDTO;
import com.im.common.domain.AdminProfileDTO;

/**
 * 后台交友资料与照片审核契约，由 im-user 实现（{@code DatingAdminSpiImpl}）。
 *
 * <p>星型架构老问题：im-admin 拿不到 {@code im_user_profile} / {@code im_user_photo}，
 * 而「翻全部人的资料、强制隐身、推荐降权、驳回某张照片」只有 im-user 能做，于是契约反转给它。
 * 判定依据是数据表归属：这几张表天然住在交友域，就不该让后台直连（同 {@link AdminVideoSpi}）。
 *
 * <p>与用户端那几个 {@code Dating*Service} 的边界：它们处处带「仅本人可改」的归属校验，
 * 而后台是管理员身份跨用户操作——复用同一个方法就得把校验绕开，绕开的那条分支迟早被误当成
 * 用户端入口。所以实现类直接对 mapper 说话，一行都不走用户端 Service。
 *
 * <p>实现方注意：本接口被调到时就说明 im-user 在场（同进程装配），但 im-admin 侧一律用
 * {@code ObjectProvider} 消费——模块没装时后台提示「模块未部署」，而不是 500。
 *
 * <p>这里的一切写操作都要落一条带 {@code operatorId} 的服务端审计日志：资料与照片是有人格利益的
 * 对象，「谁把这个人强制隐身的」必须可追，而前端传什么都不算数。
 */
public interface AdminDatingSpi {

    /**
     * 翻页查交友资料，<b>含隐身与已降权的人</b>；固定按最近活跃倒序。
     *
     * <p>没有资料行的用户不出现在结果里（{@code im_user_profile} 是 1:1 扩展表，没开过交友功能
     * 的人本来就没有这一行），后台要查这种人走用户管理那一屏，不在这里造一个空档案。
     * 分页上限与关键词长度由实现方自己夹。
     */
    PageResult<AdminProfileDTO> pageProfiles(AdminDatingQueryDTO query);

    /**
     * 强制隐身 / 恢复可被发现（改 {@code im_user_profile.discoverable}，零 DDL）。
     *
     * <p>为什么复用用户自己那个开关而不是新加「后台封禁位」列：两者语义完全一致
     * （都不进推荐流 / 附近的人 / 动态流），而两列就要在服务端写「任一为 0 即隐身」的判定，
     * 那个判定散在四个查询里，漏一处就让被封的人重新出现在卡池。
     * 代价是「本人可以自己打开」——真要硬封禁该走的是账号封禁，不是资料屏。
     *
     * <p>幂等：状态已一致就不重复写库。记录不存在抛业务异常。
     *
     * @param operatorId 操作管理员，只进服务端审计日志
     * @param reason     事由，进日志；允许为空
     * @return 变更后的一份档案，供前端就地刷新那一行
     */
    AdminProfileDTO setDiscoverable(Long userId, boolean discoverable, Long operatorId, String reason);

    /**
     * 调整推荐质量标记（{@code im_user_profile.quality_flag}：0 正常 1 低质降权 2 已升权）。
     *
     * <p>2 是<b>只有后台能写</b>的一档（真人认证），服务端任何重算都不会自动升上去；
     * 1 会与自动判定重叠——完资料度过低时本来就会被落 1，所以这里传 1 的效果是「锁定降权」，
     * 而不是新增一种状态。取值越界由实现方拒绝。
     */
    AdminProfileDTO setQualityFlag(Long userId, Integer qualityFlag, Long operatorId, String reason);

    /**
     * 翻页查照片，<b>含待审与已驳回</b>；固定按上传时间<b>升序</b>（先传来的先审，见查询 DTO 注释）。
     */
    PageResult<AdminPhotoDTO> pagePhotos(AdminPhotoQueryDTO query);

    /**
     * 审核一张照片：通过（{@code audit_status=1}）或驳回（{@code audit_status=2} + 写原因）。
     *
     * <p>驳回<b>保留行与原因</b>而不删：那是用户自己的图，他要知道哪张被拦、为什么，
     * 才知道去换一张而不是反复重传同一张。只有 1 对外可见这一判定住在本模块，后台不重复实现。
     *
     * <p>幂等：已是目标状态时直接回当前行。通过后要重算作者完资料度（照片那一档占分），
     * 驳回则不会改变「已通过张数」，实现方自己判断该不该重算。
     *
     * @param reason 驳回原因，只回给本人；允许为空但前端应提示填写
     */
    AdminPhotoDTO auditPhoto(Long id, boolean pass, String reason, Long operatorId);

    /**
     * 以管理员身份删除一张照片（逻辑删，与本人删除同一落点）。
     *
     * <p>与驳回的区别只在「行是否还在」：删除用于重复上传与明显垃圾，驳回用于「图还在但不对」。
     * {@code im_file} 里的文件本体一期不级联清理，与用户端同一口径。删的是主封面时由实现方
     * 决定是否顺移封面（与 {@code DatingPhotoService} 本人删除时的处理保持一致）。幂等。
     */
    void deletePhoto(Long id, Long operatorId);
}
