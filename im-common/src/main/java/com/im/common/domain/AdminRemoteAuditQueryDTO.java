package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台远程审计流水的查询条件，由 im-admin 组装、im-remote 执行。
 *
 * <p>{@code sessionId} 为空即<b>跨会话全局检索</b>，这是本页唯一的重查询：
 * {@code im_remote_audit_log} 只有 {@code idx_session_time (session_id, create_time)} 一条索引，
 * 按动作名或详情关键词全局过滤走不到索引。取页那一步可以让它按主键倒扫、取满就停
 * （雪花 ID 递增，倒着走主键等于倒着走时间），但分页组件要的 {@code total} 仍要付一次
 * {@code COUNT(*)}，那才是主要代价。因此实现方把全局检索的单页上限夹到 50，
 * 且后台页面<b>不对它做任何自动轮询</b>（见 {@code AdminRemoteSpi} 注释）。
 * 审计量真正长起来以后该补一条 {@code idx_action_time (action, create_time)}，
 * 那是未来的 DDL，不在本批（本批承诺零表结构变更）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminRemoteAuditQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个会话；null 表示全局检索 */
    private Long sessionId;

    /** 触发方精确过滤：null 不限，其余取 inviter / invitee / system */
    private String actor;

    /** 动作名精确过滤：null 不限（动作词表由被控端 Agent 写侧决定，后台不复制一份码表） */
    private String action;

    /** 只看高危动作（命令执行 / 电源 / 进程 / 文件读写删），与 {@code action} 二取一，本项优先 */
    private Boolean dangerOnly;

    /** 关键词：对 detail 做模糊匹配（路径、命令、拦截原因），为空不过滤 */
    private String keyword;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限（全局检索的上限比会话内更严） */
    private long size;
}
