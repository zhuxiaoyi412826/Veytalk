package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理后台用的板块分类档案（四个板块共用一张字典表），由 im-live 的 {@code AdminWorkCategorySpi} 提供。
 *
 * <p>与 {@link AdminVideoCategoryDTO} 的差别只多一个 {@link #type}：同一张
 * {@code im_live_video_category} 表按 type 分板块，后台每一屏只管自己那几行，
 * 所以返回值必须带上归属板块，否则前端无法在「图片分类页」里确认某一行不是串台来的。
 *
 * <p>视频那四个既有后台端点（{@code /api/admin/video/category*}）继续返回
 * {@code AdminVideoCategoryDTO}，不因本类而改 URL、改字段、改前端。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWorkCategoryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分类 ID（im_live_video_category 主键） */
    private Long id;

    /** 所属板块：video / image / music / novel */
    private String type;

    /** 分类名称（与各板块作品表的 category 列取值对齐） */
    private String name;

    /** 排序（升序即前端分类栏展示顺序） */
    private Integer sort;

    /** 状态：1 启用 0 停用（停用不在前端分类栏出现） */
    private Integer status;
}
