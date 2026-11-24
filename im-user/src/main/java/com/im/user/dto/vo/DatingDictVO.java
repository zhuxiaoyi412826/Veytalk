package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 交友页的下拉字典（学历 / 收入 / 婚恋状况 / 交友目的 / 排序口径）。
 *
 * <p>为什么要把枚举暴露成接口而不是让前端各写一份：这些 code 与文案同时存在于数据库注释、
 * Java 枚举与前端常量里，写第三份的那一刻就开始漂移——「学历 4」在后端是本科、
 * 在前端显示成大专这类问题，只会在这种双份字典里发生。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友资料字典")
public class DatingDictVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "学历选项（不含「未填」）")
    private List<Option> educations;

    @Schema(description = "收入区间选项（不含「未填」）")
    private List<Option> incomes;

    @Schema(description = "婚恋状况选项（不含「未填」，「不愿透露」是有效选项）")
    private List<Option> marriages;

    @Schema(description = "交友目的选项（不含「未填」）")
    private List<Option> datingGoals;

    /**
     * 卡池排序口径，取值是字符串而不是 int：{@code im_match_preference.sort_by} 就存
     * {@code 'active'} 这种可读值（建表理由见 {@code DatingSort}），所以这一档不能复用上面的
     * {@link Option}——把 code 改成字符串是唯一不丢信息的做法。
     */
    @Schema(description = "卡池排序选项（不含只在「附近的人」里有意义的 distance）")
    private List<SortOption> sorts;

    /** 一个下拉项：code 落库，desc 展示。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "字典项")
    public static class Option implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "落库 code")
        private Integer code;

        @Schema(description = "展示文案")
        private String desc;
    }

    /** 一个排序选项：code 进偏好表，desc 展示。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "排序选项")
    public static class SortOption implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "排序 code：recommend / active / hot / new")
        private String code;

        @Schema(description = "展示文案")
        private String desc;
    }
}
