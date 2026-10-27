package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminGiftRecordDTO;
import com.im.common.domain.AdminGiftRecordQueryDTO;
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

    /**
     * 送礼流水翻页（后台「钱包与礼物」那一屏的送礼页签）。
     *
     * <p>它放在本接口而不是新开一个 {@code AdminGiftRecordSpi}，理由只有一个：
     * {@code im_gift_record} 就是 im-live 持有的表，而 im-live 已经实现了本接口。
     * 为一个方法再开一份契约，结果是同一个模块要装两个 bean、后台要拿两个
     * {@code ObjectProvider}，而两者的生命周期与变更原因一模一样。
     *
     * <p><b>这一屏一个写动作都没有</b>：{@code im_gift_record} 是账本性质的只增流水，
     * 改一笔等于篡改他人之间的交易记录，删一笔会让送礼人对不上账、主播的榜也少一人。
     * 真要处置（涉诈、未成年打赏）走举报工单，处置动作是封号与冻结钱包，不是回头改历史。
     * 积分是虚拟道具，送礼即消耗，没有逆向退款通道（账本里那个 {@code refund} 是红包过期退回）。
     *
     * <p>每行的送礼人 / 收礼人昵称与房间标题都批量补齐（一页各一次 {@code IN} 查询）；
     * 用户已注销不影响该行仍然出现，房间已删除则 {@code roomTitle} 为 null。
     * 排序、单页上限与时间窗都按 {@code AdminGiftRecordQueryDTO} 的类注释执行。
     */
    PageResult<AdminGiftRecordDTO> pageGiftRecords(AdminGiftRecordQueryDTO query);
}
