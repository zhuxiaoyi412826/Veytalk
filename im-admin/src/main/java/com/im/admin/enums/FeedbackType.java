package com.im.admin.enums;

import lombok.Getter;

import java.util.Locale;

/**
 * 意见反馈的类型码表，对应 {@code im_feedback.type}。
 *
 * <p>码表收在服务端而不是让前端自由填串（与 {@link ReleasePlatform} 同一条规矩）：
 * 类型是后台列表的过滤键，允许自造码就会出现 {@code bug} 与 {@code BUG} 两个「不同类型」
 * 各自过滤、台账统计对不上的哑巴故障。新增类型必须改代码——加一种类型就该有摩擦。
 *
 * <p>{@code displayName} 是前台下拉与后台列表的中文名列：改一句中文文案不该动数据库，
 * 也不该让两个前端各抄一份。
 */
@Getter
public enum FeedbackType {

    /** 功能异常：坏了的东西 */
    BUG("bug", "功能异常"),

    /** 产品建议：希望有的东西 */
    SUGGESTION("suggestion", "产品建议"),

    /** 体验问题：能用但难用的东西 */
    EXPERIENCE("experience", "体验问题"),

    /** 其他：以上都不是 */
    OTHER("other", "其他");

    private final String code;
    private final String displayName;

    FeedbackType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 按 code 查找，未知返回 {@code null}——调用方拿到 null 应当直接拒绝请求，
     * 不回退到 OTHER：静默归类会让一个传错码的调用以为传对了，台账里多出一条
     * 谁也说不清是什么类型的反馈。
     */
    public static FeedbackType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (FeedbackType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
