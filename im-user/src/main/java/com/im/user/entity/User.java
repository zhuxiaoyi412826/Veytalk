package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 用户实体，对应表 {@code im_user}。
 *
 * <p>创建/更新时间与逻辑删除字段由 {@link BaseEntity} 统一承载。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user")
public class User extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户状态：正常 */
    public static final int STATUS_NORMAL = 1;
    /** 用户状态：禁用 */
    public static final int STATUS_DISABLED = 0;

    /** 性别：未知 */
    public static final int GENDER_UNKNOWN = 0;
    /** 性别：男 */
    public static final int GENDER_MALE = 1;
    /** 性别：女 */
    public static final int GENDER_FEMALE = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录账号，全局唯一 */
    private String username;

    /** 密码密文，任何响应都不允许带出 */
    @JsonIgnore
    private String password;

    private String nickname;

    /** 头像访问地址 */
    private String avatar;

    /** 性别：0 未知 1 男 2 女 */
    private Integer gender;

    /** 个性签名 */
    private String signature;

    /** 手机号，可空但非空时唯一 */
    private String phone;

    private String email;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;
}
