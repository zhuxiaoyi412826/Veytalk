package com.im.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 面试操作与违规事件流水：什么时候、在第几题、发生了什么。
 *
 * <p>这张表是审计定性的唯一依据，会话表上的计数只是它的展示缓存。
 * 只增不改不删，所以继承 {@link AuditEntity}（无 deleted）。
 *
 * <p>{@code eventTime} 与 {@code createTime} 是两个不同的时间，故意都留着：
 * 前者是客户端本地时刻（带毫秒，决定事件之间的先后），后者是服务端落库时刻。
 * 两者的差就是上报延迟或候选人改了系统时间的证据——只看其中一个，
 * 「攒了十分钟一次性补传」和「实时上报」在库里长得一模一样。
 *
 * <p>{@code violation} 把「违规」与「现象」分开记：session-start、turn-submit 这类
 * 流程事件也要留痕（复盘时需要知道每轮的边界），但它们不该进违规计数。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_interview_event")
public class InterviewEvent extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 切标签页或最小化（visibilitychange 不可见） */
    public static final String TYPE_VISIBILITY_HIDDEN = "visibility-hidden";
    /** 窗口失焦（blur），比切标签页更灵敏也更容易误报 */
    public static final String TYPE_BLUR = "blur";
    /** 复制 */
    public static final String TYPE_COPY = "copy";
    /** 粘贴：最典型的作弊信号——答案不是当场敲的 */
    public static final String TYPE_PASTE = "paste";
    /** 剪切 */
    public static final String TYPE_CUT = "cut";
    /** 右键菜单 */
    public static final String TYPE_CONTEXT_MENU = "contextmenu";
    /** 退出全屏 */
    public static final String TYPE_FULLSCREEN_EXIT = "fullscreen-exit";
    /** 流程事件：面试开始 */
    public static final String TYPE_SESSION_START = "session-start";
    /** 流程事件：提交一轮作答 */
    public static final String TYPE_TURN_SUBMIT = "turn-submit";
    /** 流程事件：面试结束 */
    public static final String TYPE_SESSION_END = "session-end";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属面试会话 ID */
    private Long sessionId;

    /** 候选人 ID（冗余一份：审计常按人查，不必先 join 会话表） */
    private Long userId;

    /** 事件类型，取值见 {@code TYPE_*} */
    private String eventType;

    /** 是否计入违规：1 计入会话表计数并参与阈值判断，0 仅记录现象 */
    private Integer violation;

    /** 当时进行到第几轮，0 表示尚未开始问答 */
    private Integer turnNo;

    /** 补充信息（离开多久、粘贴多少字符等），写入前截断到 500 字符 */
    private String detail;

    /** 事件发生时刻（客户端上报的本地时间，带毫秒） */
    private LocalDateTime eventTime;
}
