package com.im.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 面试会话：一场面试的元信息与各项汇总计数。
 *
 * <p>面试原先是完全无状态的——对话历史由前端 {@code Interview.vue} 持有，刷新即丢，
 * 事后既无法复盘也无从追责。这张表把它变成一条可查的业务记录。
 *
 * <p>那几个 {@code *_count} 是 {@link InterviewEvent} 流水的冗余汇总，不是事实来源：
 * 列表页要一次把「切屏 3 次」显示出来，不能为 20 行列表跑 20 次 GROUP BY。
 * 上报丢失或并发累加都可能让计数与流水不一致，所以<b>定性一律以事件表为准</b>，
 * 这里的数字只用于展示与阈值判断。
 *
 * <p>{@code durationSeconds} 由服务端用 end-start 相减得出，不信客户端自报的计时：
 * 候选人把系统时间改慢一点，自报时长就短了，而时长在审计里是要拿来对比的。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_interview_session")
public class InterviewSession extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 进行中：候选人正在答题，或页面已异常退出但还没被收尾 */
    public static final int STATUS_ONGOING = 0;
    /** 正常结束：候选人自己点了结束，或面试官给出总结后收尾 */
    public static final int STATUS_ENDED = 1;
    /** 违规达到阈值被强制结束 */
    public static final int STATUS_VIOLATION_LIMIT = 2;
    /** 未完成：刷新、关闭页面或中途离开，由巡检/下次开始时补记 */
    public static final int STATUS_INCOMPLETE = 3;

    /** 结束原因：候选人自行结束 */
    public static final String REASON_USER_END = "user-end";
    /** 结束原因：违规次数达到阈值 */
    public static final String REASON_VIOLATION_LIMIT = "violation-limit";
    /** 结束原因：刷新页面 */
    public static final String REASON_RELOAD = "reload";
    /** 结束原因：关闭页面或离开路由 */
    public static final String REASON_CLOSE = "close";
    /** 结束原因：超时未作答 / 巡检收尾 */
    public static final String REASON_TIMEOUT = "timeout";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 候选人用户 ID */
    private Long userId;

    /** 面试题目/岗位名称 */
    private String title;

    /** 状态，取值见 {@code STATUS_*} */
    private Integer status;

    /** 开始时间（服务端收到开始请求的时刻） */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 面试时长（秒），服务端计算 */
    private Integer durationSeconds;

    /** 问答轮数（面试官提问数，等同于候选人作答数） */
    private Integer turnCount;

    /** 违规总次数：下行各类合计，用于阈值判断 */
    private Integer violationCount;

    /** 切屏次数：标签页/窗口被最小化或切到其它应用 */
    private Integer blurCount;

    /** 复制次数 */
    private Integer copyCount;

    /** 粘贴次数 */
    private Integer pasteCount;

    /** 退出全屏次数（仅全屏监考模式下会产生） */
    private Integer fullscreenExitCount;

    /** 结束原因，取值见 {@code REASON_*} */
    private String endReason;

    /** 客户端屏幕分辨率（宽x高），排查切屏误报时用 */
    private String screen;

    /** 浏览器 UA 摘要（只留引擎与版本，不落完整 UA） */
    private String clientInfo;
}
