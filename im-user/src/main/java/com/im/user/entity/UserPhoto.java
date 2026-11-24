package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 照片墙的一张照片，对应表 {@code im_user_photo}（一行一张，一人最多 {@link #MAX_COUNT} 张）。
 *
 * <p>主键用雪花：同一个人可以同时有多行，而 {@code user_id} 已经被「一人一行」的
 * {@code im_user_profile} 用掉了，这里再拿它当主键就存不下第二张。
 *
 * <p>{@code url} 是服务端现拼的受控地址、<b>不采信前端传来的 URL</b>：照片墙是要在陌生人的
 * 资料卡上渲染的，如果地址能由提交方随意填写，这一栏就成了免费的钓鱼跳转位。
 *
 * <p>两个不变量由 {@code DatingPhotoService} 在每次写入后归一，DDL 表达不了：
 * <ol>
 *   <li>同一用户最多一行 {@link #isMain} 为 1（MySQL 没有部分唯一索引）；</li>
 *   <li>{@code isMain=1} 的那行恒为 {@code sort=0}——否则「设为封面」和「拖到第一位」
 *       会变成两套各自成立的状态，界面上一张封面、列表里却排在第三。</li>
 * </ol>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user_photo")
public class UserPhoto extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 照片上限：再多就没人看了，而每张都要过一次审核，配额是给审核的减负 */
    public static final int MAX_COUNT = 9;

    /** 审核状态：待人工复审（客户端机器检测降级不可用时走这里） */
    public static final int AUDIT_PENDING = 0;
    /** 审核状态：已通过，<b>只有这一档对外可见</b> */
    public static final int AUDIT_PASSED = 1;
    /** 审核状态：后台人工驳回，保留行与原因供申诉 */
    public static final int AUDIT_REJECTED = 2;

    /** 封面标记：是 */
    public static final int MAIN = 1;
    /** 封面标记：否 */
    public static final int NOT_MAIN = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** {@code im_file.id}，业务类型固定为 {@code dating_photo} */
    private Long fileId;

    /** 受控访问地址，由 {@code FileStorageSpi.accessUrl(fileId)} 生成后落库 */
    private String url;

    /** 展示顺序，升序 */
    private Integer sort;

    /** 是否主封面：1 是 0 否 */
    private Integer isMain;

    /** 单张审核状态，见 {@link #AUDIT_PENDING} 等三个常量 */
    private Integer auditStatus;

    /** 驳回原因，只在 {@link #AUDIT_REJECTED} 时有值，且只回给本人 */
    private String rejectReason;

    /** 像素宽，客户端上报，用于版式占位避免加载抖动 */
    private Integer width;

    private Integer height;

    private Long size;
}
