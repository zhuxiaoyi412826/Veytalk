package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台评论列表的查询条件，由 im-admin 组装、im-friend 执行。
 *
 * <p>与用户端「一条动态一次取全评论」完全不同：那一侧要两层结构、按时间升序、只取正常行；
 * 这一侧是跨动态的审核队列，分页、按时间倒序、含已下架行。两套需求共用一个方法只会互相拖累，
 * 所以后台单独走本查询（实现方直接 {@code selectPage}，不碰 {@code MomentCommentMapper#selectByMomentId}）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminCommentQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按评论内容模糊匹配，纯数字时额外认评论 ID */
    private String keyword;

    /** 只看某条动态下的评论：从动态那一屏点「看评论」跳过来时用 */
    private Long momentId;

    /** 只看某个评论者发的 */
    private Long userId;

    /** 状态过滤：null 全部，1 正常，2 已下架 */
    private Integer status;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限 */
    private long size;
}
