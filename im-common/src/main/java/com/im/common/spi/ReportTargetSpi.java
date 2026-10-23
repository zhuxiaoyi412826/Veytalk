package com.im.common.spi;

import com.im.common.domain.ReportDisposeCmd;
import com.im.common.domain.ReportDisposeResult;
import com.im.common.domain.ReportTargetDTO;
import com.im.common.enums.ReportDisposition;
import com.im.common.enums.ReportTargetType;

import java.util.List;

/**
 * 可举报对象的解析与处置契约，由对象归属模块实现（im-user / im-message / im-live / im-group）。
 *
 * <p>为什么要有这个接口：管理后台（im-admin）在星型架构里只依赖 im-common，
 * 不能 import {@code LiveRoomService} 或 {@code MessageMapper}。但它偏偏必须做两件只有归属模块能做的事：
 * ① 提交举报时把 {@code (targetType, targetId)} 变成一份可读的证据、并反查出责任人；
 * ② 处置时真的去封号 / 撤回消息 / 中断直播。于是把这两问收敛成一个 SPI，
 * 后台只按类型串找实现，装配在同一条 Spring 上下文里完成。
 *
 * <p>后台注入的是 {@code List<ReportTargetSpi>} 并按 {@link #targetType()} 收成 Map：
 * <b>某类对象没有实现，就代表该类不可举报</b>（提交时直接报
 * {@code REPORT_TARGET_NOT_FOUND}），而不是落一张谁都无法处理的工单。
 * 模块缺席（比如没打 im-live）时同样退化成「不可举报」，这是整条链路唯一的降级面。
 *
 * <p>{@link #dispose} 默认拒绝：只有真能改动对象状态的模块才覆写它。
 * 群聊就是典型——一期后台对群只支持「不处理 / 警告群主」，那两个动作都由后台自己完成
 * （改工单状态、推一条通知），根本不需要动群，所以 im-group 只实现 {@link #resolve}。
 */
public interface ReportTargetSpi {

    /**
     * 本实现负责的对象类型串，取值必须是 {@link ReportTargetType#getCode()} 之一。
     *
     * <p>同一个类型串被两个模块实现会导致后台装配时后者覆盖前者，工单被随机一个模块处理；
     * 新增类型时先改 {@code ReportTargetType}，再来这里加唯一实现。
     */
    String targetType();

    /**
     * 本模块实际能执行的处置动作码。
     *
     * <p>默认给出「枚举里作用域覆盖本类型」的那几个（如 user 得到 none/warn/ban_user/unban_user）。
     * 实现方若发现其中某个其实做不到，应覆写并去掉——后台据此渲染下拉，
     * 不会出现「点了没反应」的按钮。
     */
    default List<String> supportedDispositions() {
        return ReportDisposition.codesFor(ReportTargetType.of(targetType()));
    }

    /**
     * 把被举报对象解析成证据快照，并顺带反查出责任人。
     *
     * <p>提交举报与后台查看详情都会调用，必须<b>只读</b>：不许改任何状态。
     *
     * <p>实现方要注意两件事：
     * ① {@code excerpt} 自己截断（服务端限长），不要把全文塞进证据里；
     * ② 跨模块取昵称只能经 {@code UserQuerySpi} 之类的接口，不许 join 别的库表。
     *
     * @param targetId 对象 ID
     * @return 对象不存在（或已被删）时返回 {@code null}，调用方据此拒绝提交
     */
    ReportTargetDTO resolve(Long targetId);

    /**
     * 执行处置动作。
     *
     * <p>实现方应当<b>幂等</b>：重复撤回一条已撤回的消息、重复关闭一个已结束的房间，
     * 都该返回成功或一句明确的「已经结束」，而不是抛异常——工单状态已经落库了，
     * 处置这一步失败会让「工单已处置但对象没动」这种不一致留在账上。
     *
     * <p>不许在这里抛业务异常打断后台：把原因包进 {@link ReportDisposeResult#fail} 返回，
     * 管理员看得到文字比看到 5000 系统异常有用。
     *
     * @param cmd 处置指令，{@code action} 一定是 {@link #supportedDispositions()} 里的值
     */
    default ReportDisposeResult dispose(ReportDisposeCmd cmd) {
        return ReportDisposeResult.fail("该对象没有可由本模块执行的处置动作");
    }
}
