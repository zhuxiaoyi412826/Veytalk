package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理后台用的视频分类档案，由 im-live 的 {@code AdminVideoSpi} 提供。
 *
 * <p>与用户端「只回启用分类名」的 {@code /live/video/categories}（一份字符串数组）分开：
 * 后台要看到全部（含停用）、要能按 ID 改序启停，所以需要带 id/sort/status 的结构化对象，
 * 而用户端只需要名字列表。两者口径不同不共用返回值。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminVideoCategoryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分类 ID（im_live_video_category 主键） */
    private Long id;

    /** 分类名称 */
    private String name;

    /** 排序（升序即前端展示顺序） */
    private Integer sort;

    /** 状态：1 启用 0 停用 */
    private Integer status;
}
