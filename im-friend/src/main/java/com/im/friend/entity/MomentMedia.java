package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 动态的一个附件（{@code im_moment_media}），一行一张图或一个视频。
 *
 * <p>有 {@code deleted} 列，所以继承 {@link BaseEntity}：删一条动态时这些行跟着置 1，
 * 而 {@code im_file} 那边<b>不动</b>——秒传命中时同一份字节可能正被别的记录引用着
 * （与 {@code UserPhoto} 删除时的口径一致）。
 *
 * <p>{@link #url} 是写入时由服务端现拼的受控地址，不接受客户端上报：前端传什么 URL 都不算数，
 * 否则一条动态就能把浏览器指向任意外站。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_moment_media")
public class MomentMedia extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 类型：图片 */
    public static final int TYPE_IMAGE = 1;
    /** 类型：视频 */
    public static final int TYPE_VIDEO = 2;

    /**
     * 文件业务类型串，与 {@code com.im.file.enums.FileBizType.MOMENT_IMAGE} 取值一致。
     *
     * <p>重复一个字符串而不是引用那个枚举：im-friend 依赖 im-file 会形成反向依赖
     * （与 {@code DatingPhotoServiceImpl#BIZ_DATING_PHOTO} 同一处理）。
     */
    public static final String BIZ_IMAGE = "moment_image";
    public static final String BIZ_VIDEO = "moment_video";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long momentId;

    /** {@code im_file.id} */
    private Long fileId;

    /** 受控访问地址，服务端现拼 */
    private String url;

    /** 类型：1 图片 2 视频 */
    private Integer type;

    /** 九宫格内顺序，升序；首图兼作列表卡片缩略图 */
    private Integer sort;

    private Integer width;

    private Integer height;

    /** 视频时长秒，图片恒为 0 */
    private Integer duration;

    private Long size;

    public boolean isVideo() {
        return type != null && type == TYPE_VIDEO;
    }
}
