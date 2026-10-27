package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台钱包账户台账的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p><b>不提供排序参数</b>：这一屏的默认排序是「余额从高到低」，运营开它就是为了看
 * 谁手上的积分最多（异常高发处）。把 order by 交给前端等于允许一条
 * {@code ORDER BY} 任意列的语句打到 {@code im_user_wallet} 上，而这张表只有主键索引，
 * 换一列排就是换一次全表文件排序。要按人定位请用 {@code userId} 精确查。
 *
 * <p>{@code keyword} 只匹配用户 ID 与昵称，<b>不匹配账号/手机号</b>：那两列在
 * {@code im_user} 上，而钱包分页的驱动表是 {@code im_user_wallet}，跨表关键词
 * 需要先反查一批 ID（会话审计页那么做是因为设备名本来就在另一张表里）。
 * 这里改用 {@code userId} 显式传值，代价更小、意图也更清楚。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 只看某个用户的钱包行；null 不限 */
    private Long userId;

    /** 昵称关键词（模糊），null 或空白不过滤 */
    private String keyword;

    /** 只看余额大于等于该值的账户；null 不过滤 */
    private Long minBalance;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限 */
    private long size;
}
