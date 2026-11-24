package com.im.user.dto.req;

import com.im.user.entity.UserPhoto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 照片墙排序：把照片 ID 按期望的展示顺序整个传上来，服务端按下标重写 {@code sort}。
 *
 * <p>为什么不做「传一个 id + 目标下标，服务端往前往后挪」：拖拽的中间态会连续触发多次，
 * 挪位算法要么处理跨段插入的歧义，要么每次读一遍旧顺序；而整序一次性提交，
 * 九个数字一条 SQL 就写完了，天然幂等——重复提交同一个数组不会越排越乱。
 */
@Data
@Schema(description = "照片墙排序请求（按期望展示顺序提交全部照片 ID）")
public class PhotoSortRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本人照片 ID 的完整新顺序，第一个即封面", example = "[194823748239849472,194823748239849473]")
    @NotEmpty(message = "ids 不能为空")
    @Size(max = UserPhoto.MAX_COUNT, message = "一次最多排 " + UserPhoto.MAX_COUNT + " 张")
    private List<Long> ids;
}
