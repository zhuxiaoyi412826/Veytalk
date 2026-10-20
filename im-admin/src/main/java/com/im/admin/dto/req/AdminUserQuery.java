package com.im.admin.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 后台用户列表的筛选入参。
 *
 * <p>只有 {@code status} 一个额外条件加上父类的 {@code keyword}：一期没有「按注册时间区间」
 * 「按积分余额区间」这类筛法，加了就要在 im-user 那条翻页 SQL 上多引两个索引，
 * 而没人用的索引是纯负债。真需要时再加，加一个条件和加一个筛选项在前端是同一个工作量。
 *
 * <p>这里<b>不含排序参数</b>：列表口径固定为注册时间倒序（新注册又立刻被举报的人才是管理员要看的），
 * 把 orderBy 开放成 HTTP 参数等于把「前端可以传任意列名」这条路递给外面。
 * 传到 SPI 的 {@code AdminUserQueryDTO} 也只有 keyword/status/current/size 四个字段，接口形状本身就堵住了这条路。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "后台用户列表筛选入参")
public class AdminUserQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "账号状态过滤：1 正常 0 已禁用；为空查全部", example = "1")
    private Integer status;
}
