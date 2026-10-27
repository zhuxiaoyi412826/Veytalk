package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminInterviewOverviewDTO;
import com.im.common.domain.AdminInterviewQueryDTO;
import com.im.common.domain.AdminInterviewSessionDTO;

/**
 * 后台 AI 面试记录契约，由 im-ai 实现（{@code InterviewAdminSpiImpl}）。
 *
 * <h2>这一屏只有「读」</h2>
 *
 * <p>不给「删除某场面试」「修改违规计数」「手动提前结束一场正在进行的面试」任何一个写口子：
 * <ul>
 *   <li>{@code im_interview_session} 上的那些计数是<b>判罚依据</b>——「违规达阈值强制结束」
 *       这句话的成立完全依赖「这些数字是当时由写侧一口气加出来的、事后没人碰过」。
 *       后台一旦能改，这场面试的结论就再也拿不出手；</li>
 *   <li>{@code im_interview_event} 的表注释写死了<b>只增不改不删</b>，同一理由；</li>
 *   <li>真要中止一场正在作弊的面试，正当路径是让候选人自己离开，或者由
 *       {@code /api/admin/user} 禁用其账号——那是一条有权限、有留痕、有理由的处置，
 *       而不是管理员隔空点一下「结束」。顺带说清：<b>禁用账号并不能让那一屏停下来</b>，
 *       面试页是纯前端的，它只会在下一次上报时被鉴权拦住，这与远程控制那一屏的边界同理。</li>
 * </ul>
 *
 * <h2>读的是候选人的作答原文</h2>
 *
 * <p>{@code detail} 会把 {@code im_interview_message.content} 整段带回来。这正是
 * {@code ImConstants.PERM_SYSTEM_INTERVIEW} 要单独设点的原因：能禁号的人不必能看到
 * 应聘者答了什么题、怎么答的。这一屏的每一次 {@code detail} 都应该在后端日志里留下场次 ID。
 *
 * <h2>计数一律读会话表上落好的那份</h2>
 *
 * <p>违反总次数、切屏、复制、粘贴、退全屏，都是 {@code InterviewAuditService} 在
 * 阈值判断的<b>同一时刻</b>累加的。后台重算会变成「现在的配置开关下这些事件该不该算」，
 * 而配置项 {@code im.ai.proctor.count-*} 是会变的——重算出来的数与当初据以强停的数不一致，
 * 就是自己推翻自己的判罚。唯一的例外是 {@code violationByType}，它是一次
 * {@code GROUP BY event_type} 的现算，只为把「这 12 次里切屏占几次」摊开给人看。
 *
 * <h2>「进行中」里混着永远不会自己结束的僵尸行</h2>
 *
 * <p>刷新、关页、断电都会留下 {@code status = 0} 却再也不会前进的会话。
 * 候选人侧有收尾，但没有一个定时器替全站收尾，所以 {@code overview().staleOngoing}
 * 是「进行中但开始时间已超过一场面试合理时长」的行数，<b>纯提示、不自动改状态</b>：
 * 把别人的面试判成「未完成」是一次写操作，写操作的判断标准应该由发起方拿，
 * 后台看见数字异常去翻那几行就行。
 */
public interface AdminInterviewSpi {

    /**
     * 面试场次翻页，按建档时间与主键倒序。
     *
     * <p>{@code query.userId} 有值时走 {@code idx_user_time}；{@code violationOnly} 与
     * {@code minViolations} 是对 {@code violation_count} 列的过滤，<b>无索引</b>，
     * 属于「扫完再筛」，所以实现方把它当成叠加条件而不是默认视图，前端默认不勾。
     *
     * <p>返回的列表行不带 {@code qaList} / {@code events}，理由见
     * {@code AdminInterviewSessionDTO} 的类注释（正文是 TEXT，二十行各带一份会把响应撑爆）。
     */
    PageResult<AdminInterviewSessionDTO> pageSessions(AdminInterviewQueryDTO query);

    /**
     * 单场面试档案：计数 + 违规按类型拆分 + 逐轮问答 + 事件流水。
     *
     * <p>事件流水按 {@code idx_session_time} 正序取，条数由实现方夹死（一场面试的事件
     * 正常只有几十条，但 {@code maxEventsPerBatch = 100} 意味着一次上报可以塞进一百条，
     * 不夹就有被灌出几千条的可能）。
     *
     * @return 不存在返回 {@code null}
     */
    AdminInterviewSessionDTO detail(Long sessionId);

    /**
     * 顶部概览：总场次、今日、进行中、僵尸进行中、违规强停、违规率、违规榜。
     *
     * <p>{@code violationRate} 的分母是<b>已结束</b>的场次，不含进行中——把刚开考算进去
     * 会让这个比率在每个面试高峰的整点附近虚假地掉下来。
     * {@code topViolators} 是按候选人聚合的最近榜，只用于「该找谁聊聊」，不构成处罚依据。
     */
    AdminInterviewOverviewDTO overview();
}
