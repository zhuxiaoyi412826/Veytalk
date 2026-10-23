package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 处置指令：管理后台要归属模块对某个对象执行一个动作。
 *
 * <p>纯跨模块传输对象，不出现在任何 REST 响应里，因此不带 Swagger 注解。
 *
 * <p>为什么把 {@code targetType}/{@code targetId} 和 {@code reportId} 都带上：
 * 归属模块执行动作只认对象本身（撤回这条消息、关掉这个房间），
 * 而 {@code reportId} 是给实现方留的追溯线索——真要落审计日志时能写出「因哪张工单而动手」。
 * {@code operatorId} 则是权限依据：模块自己也要能判断这个管理员有没有资格做这件事。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ReportDisposeCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工单 ID（追溯用，可为空） */
    private Long reportId;

    /** 对象类型串：user / message / room / group */
    private String targetType;

    /** 对象 ID */
    private Long targetId;

    /** 责任人用户 ID，取不到为空（部分动作需要它，如封禁） */
    private Long reportedUserId;

    /** 动作码，对应 {@code ReportDisposition#getCode()} */
    private String action;

    /** 执行该动作的管理员用户 ID */
    private Long operatorId;

    /** 处置说明，实现方可用于审计备注 */
    private String remark;
}
