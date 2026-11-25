package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台用的一条动态，由 im-friend 经 {@code AdminMomentSpi} 提供。
 *
 * <p>与用户端的 {@code MomentVO} 分开：VO 是<b>受众视角</b>的产物（带「我是否赞过」、
 * 三天可见的时间窗、白名单判定结果），后台要的是全状态原始行 + 作者身份，两套口径
 * 硬拧成一个类，迟早改一边坏一边。
 *
 * <p>{@link #mediaUrls} 只为缩略图预览服务，最多给前几张（常量在实现方）：九宫格全量
 * 与视频地址在详情里另取，列表页拉满会把一页 20 条变成几百个图片请求。
 *
 * <p>{@link #mediaTypes} 与 {@link #mediaUrls} <b>同序一一对应</b>，取值同
 * {@code im_moment_media.type}（1 图片 2 视频）。它存在的唯一理由是：后台拿到一个
 * {@code /api/file/download/xxx} 时无法从地址本身判出那是图还是视频，而没有这一列就只能
 * 把视频地址当图片画——画出来是一个碎图标，「后台看不了动态视频」就是这么来的。
 * 两个列表只能由同一批行按同一顺序生成，拆不成两次查询，否则一重排序就会把对应关系弄反。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMomentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动态 ID（{@code im_moment} 主键） */
    private Long id;

    private Long userId;

    /** 作者昵称，查不到时给「已注销用户」占位 */
    private String userNickname;

    /** 正文原文，后台不截断（处置要靠读到完整上下文） */
    private String content;

    /** 可见范围：1 公开 2 仅好友 3 部分人可见 4 仅自己 */
    private Integer visibility;

    /** 附件张数（冗余列，与 {@code im_moment_media} 实际行数最终一致而非时刻一致） */
    private Integer mediaCount;

    /** 预览用的前几个附件地址，见类注释 */
    private List<String> mediaUrls;

    /** 附件类型，与 {@link #mediaUrls} 同序一一对应：1 图片 2 视频 */
    private List<Integer> mediaTypes;

    private Integer likeCount;

    private Integer commentCount;

    /** 状态：1 正常 2 已下架 */
    private Integer status;

    private LocalDateTime createTime;
}
