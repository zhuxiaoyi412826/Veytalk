package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 一次「看见你」发生在哪个入口，写进 {@code im_profile_visit.view_type}。
 *
 * <p>取值与建表注释一一对应，<b>不要复用成自增序号</b>：这张表是「谁看过我」与后续会员权益
 * 的数据源，插一个值进去会让历史行的口径全部错位。
 *
 * <p>★ 目前只有 {@link #PROFILE_CARD} 真的会写入。其余三档刻意先占着口径而不记：
 * <ul>
 *   <li>{@link #PHOTO_WALL}：照片墙与资料卡是同一个页面上先后加载的两次请求，
 *       一次浏览写两行（同一 {@code uk_visitor_visited_date} 上重复 upsert），
 *       结果 view_type 恒为「照片墙」——一次页面浏览答不出「他看了什么」这个问句；
 *   <li>{@link #MATCH_CARD}：卡池里的卡被系统推给用户 ≠ 用户看了 TA，
 *       「推过没表态」这件事已经由 {@code im_match_exposure} 精确记着，不该再冒充成一次主动访问；
 *   <li>{@link #MOMENT_LIST}：等动态（第十二批）真的有了独立入口再记。
 * </ul>
 */
@Getter
public enum ProfileVisitType {

    /** 资料卡（点进对方的交友主页） */
    PROFILE_CARD(1, "资料卡"),
    /** 照片墙 */
    PHOTO_WALL(2, "照片墙"),
    /** 动态列表 */
    MOMENT_LIST(3, "动态列表"),
    /** 匹配卡片（预留，当前不写） */
    MATCH_CARD(4, "匹配卡片");

    @JsonValue
    private final int code;
    private final String desc;

    ProfileVisitType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}。 */
    public static ProfileVisitType of(Integer code) {
        if (code != null) {
            for (ProfileVisitType value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；脏值与未匹配一律空串，让调用方自己决定要不要退回默认说法。 */
    public static String descOf(Integer code) {
        ProfileVisitType value = of(code);
        return value == null ? "" : value.desc;
    }
}
