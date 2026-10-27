package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminRemoteAuditDTO;
import com.im.common.domain.AdminRemoteAuditQueryDTO;
import com.im.common.domain.AdminRemoteOverviewDTO;
import com.im.common.domain.AdminRemoteSessionDTO;
import com.im.common.domain.AdminRemoteSessionQueryDTO;

/**
 * 后台远程控制审计契约，由 im-remote 实现（{@code RemoteAdminSpiImpl}）。
 *
 * <h2>这一屏只有「读」，一个写动作都没有</h2>
 *
 * <p>不设计「管理员强制中断会话」这个按钮，三个理由叠在一起：
 * <ol>
 *   <li><b>被控方随时能自己断</b>。Agent 侧有停止按钮，还有空闲巡检
 *       （{@code RemoteIdleCheckTask}）与邀请超时收尾，管理员掐断并不是唯一的止损路径；</li>
 *   <li><b>掐断是处置动作，该和举报工单同一条路</b>。真要阻断，正当入口是工单处置
 *       （封号 / 警告），而不是另起一个「后台一键断远程」——那会让同一件事出现两个互不知情的落点；</li>
 *   <li><b>中断需要下发帧</b>，得走 {@code RemoteRelayService} 的绑定通道，那是写侧代码；
 *       本批承诺<b>零 DDL、纯读两张已有表</b>，掺进写路径就把这一批的验收面搞大了。</li>
 * </ol>
 *
 * <p>顺带纠正一个容易踩空的认知：<b>禁用账号并不能断掉正在进行中的远程会话</b>。
 * {@code AdminUserSpiImpl#setDisabled} 走的是 {@code PushSpi.kickOut}，那一只管聊天长连接
 * （{@code WsSessionManager}）；Agent 与控制端的通道是握手期鉴权、之后不受 Sa-Token 注销影响，
 * 且中继绑定在内存里。所以「先封号再说」对远程这条链路是<b>无效动作</b>，
 * 现阶段真要阻断只能由被控端本地停掉 Agent。这个边界写在这里而不是等出事再去翻代码。
 *
 * <p>{@code accessCode} / {@code ticket} / {@code aesKey} 一律不出现在任何返回值里，
 * 理由逐条写在 {@code AdminRemoteSessionDTO} 的类注释。
 */
public interface AdminRemoteSpi {

    /**
     * 翻页查全部远程会话（不分「我的」，后台看的是全站台账）。
     *
     * <p>进行中的（active / inviting）恒排最前，其余按创建时间倒序；
     * 每行顺带补齐设备名、两端昵称、审计条数与两个实时态，批量查询，与页内条数无关。
     */
    PageResult<AdminRemoteSessionDTO> pageSessions(AdminRemoteSessionQueryDTO query);

    /**
     * 单个会话档案。
     *
     * @return 不存在时返回 {@code null}（会话表只增不删，正常只有 ID 打错才会为空）
     */
    AdminRemoteSessionDTO detail(Long sessionId);

    /**
     * 审计流水翻页，按 (createTime, id) 倒序。
     *
     * <p>排序带上 id 是必须的：审计写入密集时同一秒能落好几条，只按时间排属于
     * 「排序键不唯一」，翻页会出现同一条跨页重复或整条被跳过。
     *
     * <p>{@code query.sessionId} 为空时是跨会话全局检索，代价见
     * {@code AdminRemoteAuditQueryDTO} 的类注释。
     */
    PageResult<AdminRemoteAuditDTO> pageAudits(AdminRemoteAuditQueryDTO query);

    /** 顶部概览四个数字，不做缓存也不轮询，口径见 {@code AdminRemoteOverviewDTO} */
    AdminRemoteOverviewDTO overview();
}
