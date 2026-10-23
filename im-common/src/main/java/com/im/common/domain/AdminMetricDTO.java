package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 看板上的一格数字，由各模块经 {@code AdminMetricsSpi} 贡献。
 *
 * <p>为什么不做成固定字段（{@code totalUsers}/{@code totalRooms}/…）：那样每加一个看板指标
 * 都要改 im-common 的接口、改 im-admin 的 VO、改后台前端，三个地方来回一遍。
 * 现在是模块自己报「我这几个数」，后台原样分区渲染，加指标只改一个文件。
 *
 * <p>代价必须写明白：<b>类型不受编译器保护</b>。{@link #value} 只该放能直接显示的东西
 * （整数、字符串、已格式化的时长），不要塞集合或对象；{@link #label} 由后端出中文，
 * 前端不再维护一份指标字典，否则两处口径迟早漂移。
 * 数字取不到时应返回 {@code null} 而不是 0——前端把 null 显示成「—」，
 * 0 会被管理员读成「今天真的没人注册」。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminMetricDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 指标键，分区内唯一（如 {@code totalUsers}），供前端加样式或排查用，不参与展示 */
    private String key;

    /** 展示名（中文），由贡献模块决定 */
    private String label;

    /** 指标值，可直接显示；取不到为 {@code null}，前端显示为「—」 */
    private Object value;

    /** 口径说明，如「只统计今日零点起」；为空则不显示 */
    private String hint;
}
