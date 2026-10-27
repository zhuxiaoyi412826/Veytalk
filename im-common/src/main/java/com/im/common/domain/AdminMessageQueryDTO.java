package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台消息检索的查询条件，由 im-admin 组装、im-message 执行。
 *
 * <p><b>硬约束：{@code conversationId} / {@code fromUserId} / 时间范围三者至少给一个</b>，
 * 实现方据此拒绝「什么都不选就点搜索」。理由不是权限而是代价：
 * {@code keyword} 是对 {@code im_message.content} 做 {@code LIKE '%kw%'}，
 * 用户端的全站搜索之所以能做，是因为它先把范围收敛到「我自己的那些会话」
 * （{@code MessageServiceImpl#search} 走的是 {@code IN (我的会话)}）。
 * 后台没有这一层收敛，一条不带范围的 LIKE 就是对整张消息表的顺序扫描——
 * 而消息表是全库最大的一张表。这条规则写在实现方而不是只写在界面提示里，
 * 因为界面提示会被绕过，而 curl 不会读注释。
 *
 * <p>唯一可以不带上述条件的是「只翻最近的」：{@code keyword} 为空且没有内容过滤时，
 * 查询退化成按主键倒序取一页（雪花 ID 即时间序，所以最上面就是最近）：倒扫主键索引取满就停，是便宜的。
 * 所以实现方的判定是「<b>有 keyword 就必须有范围</b>」，而不是无条件的三者其一。
 *
 * <p>注意 {@code startTime} / {@code endTime} 在这里是<b>过滤条件而不是排序键</b>，
 * 与 {@code AdminMessageSpi#pageMessages} 的说法一致：拿 {@code send_time} 排序，
 * 在带 {@code keyword} 时会对整个命中集 filesort。
 *
 * <p>默认结果<b>包含已撤回的行</b>（不筛 {@code is_recalled}）：管理员查的往往正是
 * 「他发过什么、后来撤了什么」，撤回本身就是要看的事件，不是要从结果里抹掉的东西；
 * 要只看撤回过的，把 {@code recalledOnly} 拉上。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMessageQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个会话 */
    private Long conversationId;

    /** 限定某个发送者（命中 {@code uk_from_client} 的 from_user_id 前缀） */
    private Long fromUserId;

    /** 消息类型精确过滤：1~14，见 {@code MsgType}；null 全部 */
    private Integer msgType;

    /** 正文关键词（前后都带 % 的模糊匹配） */
    private String keyword;

    /** 只看已撤回的（{@code is_recalled=1}）；null 或 false 不限（默认就含已撤回的行） */
    private Boolean recalledOnly;

    /** 发送时间范围起点（含），命中 {@code idx_send_time} */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 发送时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方夹到 50 */
    private long size;
}
