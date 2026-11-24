package com.im.user.dto.req;

import com.im.user.entity.UserTag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 保存本人兴趣标签：<b>整组替换</b>语义，数组即最终结果与展示顺序。
 *
 * <p>为什么不是 {@code add} / {@code remove} 两个接口：标签选择器交互上是「点选若干 → 一次性保存」，
 * 拆成两个增量接口就得让前端自己保证两次请求之间没人改过，而全量提交的比对天然幂等
 * （同一个数组提交两次，第二次是零写入）。代价是并发编辑会后写覆盖先写——
 * 改的是自己的兴趣，这个取舍可以接受；照片墙要跨多次上传逐步累积，才需要另一套按 id 操作。
 *
 * <p>传空数组是<b>清空全部</b>的有效表达（所以 {@code ids} 只能要求非 null，不能要求非空）。
 */
@Data
@Schema(description = "保存兴趣标签请求")
public class TagSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "按期望展示顺序提交的标签 ID 数组（im_tag.id），最多 "
            + UserTag.MAX_SELECT + " 个；空数组即清空", example = "[17, 33, 25]")
    @NotNull(message = "ids 不能为空，清空请传 []")
    @Size(max = UserTag.MAX_SELECT, message = "一次最多提交 " + UserTag.MAX_SELECT + " 个标签")
    private List<Long> ids;
}
