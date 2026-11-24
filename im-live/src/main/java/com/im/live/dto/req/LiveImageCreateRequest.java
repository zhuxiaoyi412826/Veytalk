package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 发布 / 编辑图集请求（{@code POST} 与 {@code PUT} 共用一个形状）。
 *
 * <p>前端先用 {@code uploadFileSmart} 把 1~9 张图（bizType=image_work）传到 {@code im_file}，
 * 拿到 {@code fileId} 列表后再调本接口建记录。<b>只传 ID，不传地址</b>：受控地址由服务端
 * 按配置前缀拼，理由同 {@link LiveVideoCreateRequest}。图片板块没有独立的封面字段——
 * 封面恒等于首图，所以上传时不再单独传一张封面文件。
 *
 * <p>{@code PUT} 复用本类是刻意的：编辑弹窗把整表单预填后整体提交，语义是「整条替换」而不是
 * 「哪字段非空就改哪」。局部更新的接口一旦和整体提交混在一起，「清空封面」就永远做不到
 * （null 既是「不改」又是「要清空」）。张数区间（1~9）在服务层判并回 {@code BAD_REQUEST}，
 * 不走 JSR-303：那里失败的 code 是 VALIDATE_FAILED，而这条是业务口径而非字段格式。
 */
@Data
@Schema(description = "发布/编辑图集请求")
public class LiveImageCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "图集文件 ID，顺序即展示顺序（1~9 个，首图作封面）")
    @NotEmpty(message = "至少要选一张图")
    private List<Long> imageIds;

    @Schema(description = "作品标题", example = "周末的山间")
    @Size(max = 60, message = "标题最多 60 字")
    private String title;

    @Schema(description = "分类标签（图片板块二级分类），可空——空则服务端落 综合", example = "风景")
    @Size(max = 32, message = "分类最多 32 字")
    private String category;

    @Schema(description = "各图字节数合计，可空——空则落 0")
    private Long size;

    @Schema(description = "首图扩展名，不含点", example = "jpg")
    private String ext;

    @Schema(description = "首图 MIME 类型", example = "image/jpeg")
    private String contentType;
}
