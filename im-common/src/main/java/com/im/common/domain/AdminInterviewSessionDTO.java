package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一场 AI 面试的后台档案（{@code im_interview_session}），由 im-ai 经 {@code AdminInterviewSpi} 提供。
 *
 * <p>与候选人自己看的 {@code /api/ai/interview/session/{id}} 的区别：本人那一屏只给
 * 「自己的、按归属校验过的」数据，而后台要按<b>候选人 ID 或场次 ID</b> 直接取，
 * 且要多读两列排障用的元数据——{@code clientInfo} 与 {@code screen}（切屏误报靠它们对质）。
 *
 * <p>列表行与档案共用本类：{@code qaList} / {@code events} / {@code violationByType}
 * 三列<b>只在档案里有值</b>，列表行一律为 null。理由是体积：一场面试的问答正文是 TEXT，
 * 二十行场次各带一份正文会把一个分页响应撑到几百 KB，而列表页根本不需要读正文。
 *
 * <p>所有计数列都<b>读会话表上落好的那份</b>，不在后台重算：{@code violation_count} 等列
 * 是写侧（{@code InterviewAuditService}）在阈值判断的同一时刻累加的，重算一遍就会
 * 和「当时真的把这场面试强制结束掉的那个值」不一致，而后者才是有法律意义的数。
 * 唯一的例外是 {@code violationByType}，它是一次 {@code GROUP BY event_type} 的现算，
 * 只为让管理员看清「这 12 次违规里切屏占几次」。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminInterviewSessionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 面试会话 ID（雪花） */
    private Long sessionId;

    /** 候选人用户 ID */
    private Long userId;

    /** 候选人昵称（批量补，查不到给「已注销用户」） */
    private String candidateName;

    /** 面试题目/岗位名称 */
    private String title;

    /** 状态：0 进行中 1 正常结束 2 违规达阈值强制结束 3 未完成（刷新/关页/中途离开） */
    private Integer status;

    /** 开始时间（服务端收到开始请求的时刻） */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 面试时长（秒），服务端按 end − start 算，不信客户端自报 */
    private Integer durationSeconds;

    /** 问答轮数 */
    private Integer turnCount;

    /** 违规总次数（四类下行违规合计 + 未单列的违规事件） */
    private Integer violationCount;

    /** 切屏次数 */
    private Integer blurCount;

    /** 复制次数 */
    private Integer copyCount;

    /** 粘贴次数 */
    private Integer pasteCount;

    /** 退出全屏次数 */
    private Integer fullscreenExitCount;

    /** 结束原因：user-end / violation-limit / reload / close / timeout */
    private String endReason;

    /** 客户端屏幕分辨率（宽x高），排切屏误报时用 */
    private String screen;

    /** 浏览器 UA 摘要（只留引擎与版本，不含完整 UA） */
    private String clientInfo;

    /** 建档时间 */
    private LocalDateTime createTime;

    /** 违规按类型拆分，仅档案有值：key 是 {@code event_type}，value 是计数 */
    private Map<String, Long> violationByType;

    /** 问答逐轮列表，仅档案有值，按 {@code seq} 正序 */
    private List<QaItem> qaList;

    /** 操作与违规事件流水，仅档案有值，按 {@code eventTime} 正序、条数由实现方夹死 */
    private List<EventItem> events;

    /**
     * 一轮问答。面试官的提问与候选人的作答是<b>两行</b>（幂等键是「会话 + 轮次 + 角色」），
     * 所以这里一行一条，前端按 {@code role} 分左右气泡即可。
     */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QaItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 会话内序号，从 1 递增；排序以此为准，不用时间（同毫秒会并列） */
        private Integer seq;

        /** 第几轮 */
        private Integer turnNo;

        /** 角色：assistant 面试官提问 / user 候选人作答 */
        private String role;

        /** 正文 */
        private String content;

        /** 正文字符数 */
        private Integer charCount;

        /** 候选人从收到本题到提交的耗时（毫秒）；提问行为 0 */
        private Long elapsedMs;

        /** 落库时间 */
        private LocalDateTime createTime;
    }

    /**
     * 一条操作/违规事件。
     *
     * <p>{@code eventTime} 是<b>客户端上报的本地时间</b>，{@code createTime} 是服务端落库时间，
     * 两者的差就是上报延迟或客户端时钟偏差——审计时要并列给出，不能只给一个，
     * 否则「他在第 3 分 12 秒切屏」这句话会因为客户端表针不准而指错人。
     */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 事件 ID */
        private Long eventId;

        /** 类型：visibility-hidden / blur / copy / paste / cut / contextmenu / fullscreen-exit / session-start / turn-submit / session-end */
        private String eventType;

        /** 是否计入违规：1 计入并参与阈值判断，0 仅记录现象 */
        private Integer violation;

        /** 当时进行到第几轮，0 表示尚未开始问答 */
        private Integer turnNo;

        /** 补充信息（离开多久、粘贴多少字符等），写入时已截到 500 字符 */
        private String detail;

        /** 客户端发生时刻 */
        private LocalDateTime eventTime;

        /** 服务端落库时刻 */
        private LocalDateTime createTime;
    }
}
