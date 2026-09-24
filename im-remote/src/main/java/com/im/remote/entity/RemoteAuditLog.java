package com.im.remote.entity;

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

/**
 * 远程会话审计日志：谁在什么时间对哪台设备做了什么。
 *
 * <p>写入来源两条：Agent 把高危操作（文件删除、结束进程、cmd 执行、电源指令）
 * 通过 audit 帧上报；服务端把无法由 Agent 自证的动作（会话建立/结束、只读模式下的
 * 输入拦截）直接落库。日志只增不改不删，因此继承 {@code AuditEntity} 且物理永远不会用到 update。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_remote_audit_log")
public class RemoteAuditLog extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属远程会话 ID */
    private Long sessionId;

    /** 动作标识：session-start / session-end / file-rm / ps-kill / exec / power / input-blocked ... */
    private String action;

    /** 动作详情（路径、命令、拦截原因等），写入前截断到 1000 字符 */
    private String detail;
}
