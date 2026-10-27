package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminGroupDTO;
import com.im.common.domain.AdminGroupMemberQueryDTO;
import com.im.common.domain.AdminGroupQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminGroupSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台群组管理：群台账、单群档案、成员分页、强制解散、全员禁言。
 *
 * <p>薄封装，本类不读 {@code im_group} / {@code im_group_member}，一切经 {@link AdminGroupSpi}
 * 交给 im-group；两个写动作在 im-group 内部再收敛到 {@code GroupService} 的 {@code force} 分支
 * （与群主自己操作共用 {@code doDismiss} / {@code doMuteAll} 那同一段代码）。
 *
 * <p>后台在群里<b>能做的只有这两件事</b>：把一个正在滋事的群掐掉，或者让除了组织者以外的
 * 所有人闭嘴。改群名、换群主、踢某个成员、设管理员都不做——那些是群内治理，是群主的权利；
 * 管理员替群主主持人事，会让一个百人群的日常秩序变成平台可随手干预的东西，
 * 而 {@code system:group:manage} 的授权理由是「阻断」，不是「接管」。
 *
 * <p>两个写动作都把处置后的最新档案回给前端（{@code forceDismiss} 连「已经解散过了」
 * 也当成功返回当前档案），这样按钮连点两次不会出现「第二次失败所以不知道到底散没散」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGroupService {

    private final ObjectProvider<AdminGroupSpi> groupSpiProvider;

    public PageResult<AdminGroupDTO> page(AdminGroupQueryDTO query) {
        return requireSpi().pageGroups(query);
    }

    /**
     * 单群档案：群信息 + 最近成员快照 + 最近入群申请（含已处理）+ 机器人（含已停用）。
     *
     * <p>与列表不同，这里<b>包含已解散的群</b>且不做过滤：强制解散不可逆，
     * 「这个群什么时候没的、当时还有多少人」正是事后要查的东西。
     */
    public AdminGroupDTO detail(Long groupId) {
        BusinessException.throwIf(groupId == null, ResultCode.BAD_REQUEST, "群 ID 必填");
        AdminGroupDTO group = requireSpi().detail(groupId);
        if (group == null) {
            throw new BusinessException(ResultCode.GROUP_NOT_FOUND);
        }
        return group;
    }

    public PageResult<AdminGroupDTO.MemberItem> pageMembers(AdminGroupMemberQueryDTO query) {
        BusinessException.throwIf(query.getGroupId() == null, ResultCode.BAD_REQUEST, "群 ID 必填");
        return requireSpi().pageMembers(query);
    }

    /**
     * 强制解散，等价于群主亲自解散，只是不查操作人的群内角色。
     *
     * <p>不可逆：群状态置 0、成员行全部置为已退群，之后没有任何恢复入口
     * （恢复要重新生成成员关系与入群时间，那是造数据而不是撤销）。
     */
    public AdminGroupDTO forceDismiss(Long groupId, Long adminId) {
        AdminGroupSpi spi = requireSpi();
        AdminGroupDTO group = spi.forceDismiss(groupId, adminId);
        log.info("[后台群组管理] 强制解散完成: groupId={}, admin={}", groupId, adminId);
        return group;
    }

    /** 强制开 / 关全员禁言，可逆；豁免规则沿用群内禁言那套（群主与管理员不受限） */
    public AdminGroupDTO forceMuteAll(Long groupId, boolean muteAll, Long adminId) {
        AdminGroupSpi spi = requireSpi();
        AdminGroupDTO group = spi.forceMuteAll(groupId, muteAll, adminId);
        log.info("[后台群组管理] 全员禁言{}: groupId={}, admin={}", muteAll ? "开启" : "解除", groupId, adminId);
        return group;
    }

    private AdminGroupSpi requireSpi() {
        AdminGroupSpi spi = groupSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "群组模块未装配，后台无法管理群组");
        }
        return spi;
    }
}
