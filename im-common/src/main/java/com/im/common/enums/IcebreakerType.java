package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 一条破冰文案是哪一种用法，写进 {@code im_icebreaker.type}。
 *
 * <p>取值与建表注释、以及 V1.0.7 里 55 行种子的 id 分段一一对应（打招呼 1~20、话题卡 21~40、
 * 标签定制开场白 41~55），<b>不要复用成自增序号</b>：后台按 type 分三个页签维护，
 * 插一个值进去会让「这一条该出现在哪个位置」失去答案。
 *
 * <p>三档的差别不在文案长短，而在<b>能不能不看着对方就给</b>：
 * <ul>
 *   <li>{@link #GREETING} / {@link #TOPIC} 是 {@code tag_id = 0} 的通用文案，
 *       谁都能用，所以它们可以缓存、可以整库下发；</li>
 *   <li>{@link #TAG_OPENER} 绑定一个具体标签，<b>只在双方都选了这个标签时才出现</b>——
 *       「你也练健身？」发给一个没填健身的人不是破冰，是穿帮。</li>
 * </ul>
 */
@Getter
public enum IcebreakerType {

    /** 打招呼模板：第一句话，可直接复制去发 */
    GREETING(1, "打招呼模板"),
    /** 话题卡：不是开场白，而是聊起来之后接不下去时抛的问题 */
    TOPIC(2, "话题卡"),
    /** 标签定制开场白：按双方共有的兴趣标签挑出来的那一句 */
    TAG_OPENER(3, "标签开场白");

    @JsonValue
    private final int code;
    private final String desc;

    IcebreakerType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查，未匹配返回 {@code null}。 */
    public static IcebreakerType of(Integer code) {
        if (code != null) {
            for (IcebreakerType value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 展示文案；脏值与未匹配一律空串，让调用方自己决定要不要退回默认说法。 */
    public static String descOf(Integer code) {
        IcebreakerType value = of(code);
        return value == null ? "" : value.desc;
    }
}
