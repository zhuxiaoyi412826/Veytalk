package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 权限点实体，对应表 {@code im_permission}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_permission")
public class Permission extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 权限标识，如 message:send */
    private String permCode;

    private String permName;

    /** 所属模块：user/friend/message/group/file/system */
    private String module;

    private String description;

    /** 状态：1 启用 0 停用 */
    private Integer status;
}
