package com.im.friend.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 动态的一个附件。
 *
 * <p>{@code url} 是服务端现拼的受控地址，前端必须走统一的 {@code mediaUrl(url)} 渲染
 * （与照片墙同一口径）：这个地址需要登录态，直接当 {@code img src} 会在跨域与鉴权上翻车。
 *
 * <p>{@code fileId} <b>不下发</b>：动态的附件是「看」的，不是「管」的，
 * 拿到文件 ID 的人可以试着走文件下载接口，而那条路径的鉴权依据是「文件所在会话的成员」，
 * 动态附件根本不在任何会话里。发布时校验过归属就够了，读的时候没必要再把 ID 交出去。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "动态附件")
public class MomentMediaVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "附件 ID")
    private Long mediaId;

    @Schema(description = "受控访问地址，前端需经统一的媒体地址处理后再渲染")
    private String url;

    @Schema(description = "类型：1 图片 2 视频")
    private Integer type;

    @Schema(description = "九宫格内顺序，升序；首项兼作卡片缩略图")
    private Integer sort;

    @Schema(description = "像素宽，0 表示客户端没上报")
    private Integer width;

    @Schema(description = "像素高，0 表示客户端没上报")
    private Integer height;

    @Schema(description = "视频时长（秒），图片为 0")
    private Integer duration;
}
