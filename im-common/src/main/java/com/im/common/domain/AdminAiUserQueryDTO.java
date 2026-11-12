package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 用量按人聚合的查询条件（im-admin 组装、im-ai 执行）。
 *
 * <p>排序固定在实现方：累计耗时（{@code totalMs}）倒序——这一屏要回答的第一个问题
 * 就是「谁烧得最多」，把烧钱榜排最前。不提供按昵称模糊搜：聚合发生在
 * {@code im_ai_usage} 上，昵称在 im-user 的表里，跨模块做 LIKE 要么拼 JOIN
 * 要么全量聚合后内存过滤（翻页语义就假了），而管理员找到人的常规路径是
 * 从用户管理那屏拿到 ID 再进来，{@code userId} 精确过滤足够。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAiUserQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 精确限定某个用户，null 看全员 */
    private Long userId;

    /** 只看已被封禁 AI 功能的账号 */
    private Boolean bannedOnly;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方自己夹上限 */
    private long size;
}
