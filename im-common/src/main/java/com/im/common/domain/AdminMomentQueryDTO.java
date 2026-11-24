package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台动态列表的查询条件，由 im-admin 组装、im-friend 执行。
 *
 * <p>翻页固定按 ID 倒序（雪花 ID 等价于发布时间倒序，与用户端 Feed 同一口径），不给排序参数：
 * 理由见 {@link AdminVideoQueryDTO}。
 *
 * <p>这里的 {@code userId} 与 {@code visibility} 是<b>用户端 Feed 拿不到的两把钥匙</b>：
 * 前台永远只能按「受众」取数，而后台要能按作者捞一个人的全部动态（含已下架的），
 * 否则投诉「某人刷屏」就没有处置入口。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMomentQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按正文模糊匹配，纯数字时额外认动态 ID 或作者 ID */
    private String keyword;

    /** 作者过滤：null 不限 */
    private Long userId;

    /** 状态过滤：null 全部，1 正常，2 已下架 */
    private Integer status;

    /** 可见范围过滤：null 全部，1 公开 2 仅好友 3 部分人 4 仅自己 */
    private Integer visibility;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限 */
    private long size;
}
