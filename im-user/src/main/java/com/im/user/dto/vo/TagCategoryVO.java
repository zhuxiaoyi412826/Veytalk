package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 标签库按分类分好组的一层（选择器一个分类一行）。
 *
 * <p>分组在服务端做而不是前端 {@code reduce}：分类的先后顺序是「按该分类最小的 {@code sort}」，
 * 也就是运营在标签库里排出来的顺序；这条规则在前端再实现一遍，后台调整顺序时就会两边各自生效一半。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "兴趣标签分类分组")
public class TagCategoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分类名")
    private String category;

    @Schema(description = "该分类下启用中的标签，按库内 sort 升序")
    private List<TagVO> tags;
}
