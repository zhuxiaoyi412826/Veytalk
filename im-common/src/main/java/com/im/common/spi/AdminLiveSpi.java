package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminLiveDTO;
import com.im.common.domain.AdminLiveQueryDTO;

/**
 * 后台直播管理契约，由 im-live 实现（{@code LiveAdminSpiImpl}）。
 *
 * <p>为什么不复用既有的 {@code LiveRoomSpi}：那个面向连麦信令（isLiving / anchorIdOf /
 * publishLinkmic），是「运行中房间」的实时问答；后台要的是<b>场次台账</b>——全部历史翻页、
 * 已封禁场次可查、强制关播。两组方法生命周期不同，混在一个接口里迟早有人改信令时误伤后台。
 *
 * <p><b>中断只经 {@code LiveRoomService#stop(roomId, "ban")} 这一个落点</b>：
 * 举报处置（{@code ReportRoomTargetSpiImpl}）也走它。两处关房必然一处断弹幕、
 * 一处忘清心跳键。ban 分支天生跳过「必须是主播本人」的归属校验，正合管理端语义。
 *
 * <p>{@code streamKey} 绝不出现在本接口任何返回值里——与举报证据同一条红线。
 */
public interface AdminLiveSpi {

    /**
     * 翻页查直播场次，<b>含全部历史与已封禁</b>（不受用户端大厅「结束超 1 小时隐藏」规则约束）。
     * 直播中的恒排最前，其余按开播时间倒序；分页上限由实现方自己夹。
     */
    PageResult<AdminLiveDTO> pageRooms(AdminLiveQueryDTO query);

    /**
     * 单场档案。
     *
     * @return 不存在（含被 abort 真删的行）时返回 {@code null}
     */
    AdminLiveDTO detail(Long roomId);

    /**
     * 强制关播（status → 3 已封禁，endReason=ban）：断弹幕通道、清心跳与门禁、删回放入口。
     *
     * <p>幂等：<b>不在直播中的房间静默返回</b>（工单排队期间主播已自己关播是最常见情况），
     * 房间不存在抛业务异常。BANNED 是终态，一期不提供解封——解封等于允许原样续播，
     * 而 streamKey 已置空、分片目录已过期，续播是个说谎的按钮。
     *
     * @param roomId     目标房间
     * @param operatorId 操作管理员，进服务端审计日志
     */
    void forceStop(Long roomId, Long operatorId);
}
