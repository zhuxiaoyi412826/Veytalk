package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台照片审核用的一行，对应 {@code im_user_photo} 的一条记录。
 *
 * <p>为什么单独一个类而不塞进 {@link AdminProfileDTO} 的列表里：驳回是<b>按单张</b>发生的，
 * 一个「整份资料一个审核状态」的字段表达不了「第三张违规、其余八张正常」。
 * 资料屏只需要知道张数，具体某张的图与理由都在这一屏。
 *
 * <p>{@link #url} 是落库的受控访问地址（{@code /api/file/download/{fileId}}），不是短时效预签名地址：
 * 审核一屏可能停几分钟，过期就 404 的预览链接不如没有。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminPhotoDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 照片记录 ID（{@code im_user_photo} 主键） */
    private Long id;

    private Long userId;

    /** 所属用户昵称，查不到时给「已注销用户」占位 */
    private String userNickname;

    /** {@code im_file.id} */
    private Long fileId;

    /** 图片受控访问地址 */
    private String url;

    /** 展示顺序，升序 */
    private Integer sort;

    /** 是否主封面：1 是 0 否 */
    private Integer isMain;

    /** 审核状态：0 待审 1 通过 2 驳回 */
    private Integer auditStatus;

    /** 驳回原因，只有 {@code auditStatus=2} 时有值 */
    private String rejectReason;

    private Integer width;

    private Integer height;

    /** 字节数 */
    private Long size;

    private LocalDateTime createTime;
}
