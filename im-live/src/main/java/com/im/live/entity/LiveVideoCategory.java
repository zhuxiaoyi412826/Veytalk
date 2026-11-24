package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 直播页板块二级分类字典，对应 {@code im_live_video_category}。
 *
 * <p>驱动前端顶部分类栏与发布分类下拉，由后台增删改（不再硬编码在前端）。{@link #name}
 * 与作品表的 {@code category} 列取值对齐——发布时选的分类就是这里某一行的 name；
 * {@link #sort} 升序即前端展示顺序；{@link #status} 为 0（停用）时不在前端分类栏出现，
 * 但已有作品仍保留其 category 文本（停用不等于删除，也不批量回改历史行）。
 *
 * <p>表名仍叫 {@code im_live_video_category} 但<b>一张表吃四个板块</b>：靠 {@link #type}
 * 区分 video / image / music / novel。改名代价（历史台账、备份表名、V1.0.4 已登记的脚本名）
 * 远大于收益，而「分类栏」这件事本来就该只有一套口径与一份同名校验。
 * 存量行在 V1.0.6 里自动落 {@code video}。
 *
 * <p>逻辑删除继承 {@link BaseEntity} 的 {@code deleted}：删掉一行只是从字典隐身，
 * 不动任何已发布作品。唯一性（同名）在 {@code LiveVideoCategoryService} 里对
 * <b>同板块未删除行</b>校验，不在库上加唯一索引——加了会让「删了又想起用同名」被历史行卡住，
 * 而同名校验一旦跨板块就错（图片板块的「综合」不该挡住小说板块的「综合」）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_live_video_category")
public class LiveVideoCategory extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：启用（在前端分类栏出现） */
    public static final int STATUS_ENABLED = 1;
    /** 状态：停用（不在前端分类栏出现，历史记录不动） */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属板块：video / image / music / novel（取 {@code WorkBoardType} 的 code） */
    private String type;

    /** 分类名称（与各板块作品表的 category 取值对齐），最长 32 */
    private String name;

    /** 排序（升序，前端分类栏按此顺序展示） */
    private Integer sort;

    /** 状态：1 启用 0 停用 */
    private Integer status;
}
