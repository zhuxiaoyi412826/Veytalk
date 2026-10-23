package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 被举报对象类型，写进 {@code im_report.target_type}。
 *
 * <p>用字符串而不是 TINYINT：这张表要同时指向四张不同业务的表，
 * {@code target_id} 单独看毫无意义，必须和类型串在一起才读得懂；
 * 数字编码在日志与工单列表里还得多查一次字典，排查时反而更慢。
 *
 * <p>这个枚举是「举报能指向谁」的唯一口径：新增一种可举报的对象，
 * 除了在这里加值，还要在归属模块提供一个同名 {@code targetType()} 的
 * {@code ReportTargetSpi} 实现，否则提交举报时找不到解析器，直接报
 * {@code REPORT_TARGET_NOT_FOUND}（而不是静默落一条没人能处理的工单）。
 */
@Getter
public enum ReportTargetType {

    /** 用户（资料卡 / 头像 / 昵称） */
    USER("user", "用户"),
    /** 单条消息（消息右键「举报」） */
    MESSAGE("message", "消息"),
    /** 直播间（一场直播，指向 im_live_room） */
    ROOM("room", "直播间"),
    /** 群聊（群名称 / 群简介 / 群公告） */
    GROUP("group", "群聊");

    @JsonValue
    private final String code;
    private final String desc;

    ReportTargetType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按类型串反查枚举，忽略大小写（前端可能传 {@code USER}）。
     *
     * @return 未匹配时返回 {@code null}，由调用方决定拒绝方式
     */
    public static ReportTargetType of(String code) {
        if (code != null) {
            for (ReportTargetType value : values()) {
                if (value.code.equalsIgnoreCase(code.trim())) {
                    return value;
                }
            }
        }
        return null;
    }
}
