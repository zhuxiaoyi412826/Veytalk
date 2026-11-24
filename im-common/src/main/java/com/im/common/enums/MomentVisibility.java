package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 单条动态的可见范围，对应 {@code im_moment.visibility}。
 *
 * <p>★ 「公开」不是全站公开：动态的受众恒为<b>我的好友与我配对过的人</b>（外加我自己），
 * 这一档只是「受众里所有人都能看」。交友产品把陌生人放进信息流等于把资料卡变成可爬的公开页，
 * 而 {@code im_moment} 也没有为「全站可见」准备任何索引与审核链路。
 *
 * <p>与 {@link MomentVisibleDays} 刻意分成两个东西：本枚举是<b>发这一条时</b>的决定，
 * 那个是<b>对全部历史动态</b>的时间口径。合成一个字段就会出现「改了偏好把三年前的动态也藏了」
 * 或者反过来「设了三天可见但某条单独选了公开就永远挂着」。
 */
@Getter
public enum MomentVisibility {

    /** 受众内所有人都能看（好友 + 配对对象） */
    PUBLIC(1, "公开"),
    /** 只有好友能看，配对但没加好友的人看不到 */
    FRIENDS_ONLY(2, "仅好友"),
    /** 只有 {@code im_moment_visible} 白名单里的人能看 */
    PARTIAL(3, "部分人可见"),
    /** 只有自己能看（发出去当记事本用，或者先存着以后再放出来） */
    SELF_ONLY(4, "仅自己");

    @JsonValue
    private final int code;
    private final String desc;

    MomentVisibility(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 按 code 取枚举。
     *
     * <p>未匹配返回 {@code null} 而<b>不退到 {@link #PUBLIC}</b>：这是隐私位，
     * 脏值退到最宽的一档等于把「库里被手改成一个没人认识的值」变成对所有人可见。
     * 调用方拿到 null 应当直接拒绝写入或按最严的一档展示。
     */
    public static MomentVisibility of(Integer code) {
        if (code == null) {
            return null;
        }
        for (MomentVisibility item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }

    /** 展示文案；脏值与未匹配一律空串。 */
    public static String descOf(Integer code) {
        MomentVisibility item = of(code);
        return item == null ? "" : item.desc;
    }

    /** 这一档要不要读白名单表：只有 {@link #PARTIAL} 要，其余三档的判据都在关系侧。 */
    public boolean needsWhitelist() {
        return this == PARTIAL;
    }
}
