package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标签库里的一个标签（选择器上的一个 chip）。
 *
 * <p>这里给的是 {@code im_tag.id}，不是 {@code im_user_tag.id}：绑定行 ID 是内部实现，
 * 让前端拿它当 key 就等于把「一个标签只能有一条绑定」这个约束写进了 UI，
 * 而保存接口的入参本来就只需要标签本身的身份。
 *
 * <p>★ <b>顺序只看数组下标，不另设 sort 字段</b>：列表接口的排序来源有两个（选择器里是库内
 * {@code im_tag.sort}，资料卡上是用户自己拖的 {@code im_user_tag.sort}），再放一个字段就等于
 * 让前端去猜这次返回的是哪一种。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "兴趣标签")
public class TagVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "标签 ID（im_tag.id）")
    private Long tagId;

    @Schema(description = "标签名，chip 文案")
    private String name;

    @Schema(description = "所属分类，选择器分组用")
    private String category;

    @Schema(description = "图标名，为空时前端吃分类默认图标")
    private String icon;

    @Schema(description = "被多少用户在用（冗余计数，只作展示与热度排序，不参与打分）")
    private Integer useCount;

    @Schema(description = "该标签当前是否已停用：只可能出现在他人资料卡上（停用的已绑定标签保留），"
            + "选择器里不会给停用标签这一项")
    private Boolean disabled;
}
