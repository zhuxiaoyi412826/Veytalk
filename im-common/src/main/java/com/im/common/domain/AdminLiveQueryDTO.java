package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台直播场次列表的查询条件，由 im-admin 组装、im-live 执行。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminLiveQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按房间标题模糊匹配，为空不过滤 */
    private String keyword;

    /** 状态过滤：null 全部，1 直播中，2 已结束，3 已封禁 */
    private Integer status;

    /** 主播过滤：null 不限 */
    private Long anchorId;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
