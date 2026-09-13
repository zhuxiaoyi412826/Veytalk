package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import com.im.common.enums.RequestStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 好友申请记录。
 *
 * <p>表中还有一个虚拟生成列 {@code pending_flag}（{@code IF(status=0,1,NULL)}）配合唯一键
 * {@code uk_from_to_pending} 实现「同一对用户最多一条待处理申请」，同时保留全部历史处理记录。
 * 生成列不可写，因此这里<b>刻意不声明该字段</b>，避免 MyBatis-Plus 把它拼进 INSERT 列清单。
 *
 * <p>并发插入重复申请时数据库会抛 {@code DuplicateKeyException}，
 * Service 层捕获后转换为友好错误码 {@code FRIEND_APPLY_DUPLICATE}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_friend_request")
public class FriendRequest extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 申请来源 */
    public static final String SOURCE_SEARCH = "search";
    public static final String SOURCE_QRCODE = "qrcode";
    public static final String SOURCE_GROUP = "group";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 申请人 ID */
    private Long fromUserId;

    /** 被申请人 ID */
    private Long toUserId;

    /** 验证消息 */
    private String verifyMessage;

    /** 来源：search / qrcode / group */
    private String source;

    /** 状态：0 待处理 1 已同意 2 已拒绝 3 已过期 */
    private Integer status;

    /** 处理时间，待处理时为 null */
    private LocalDateTime handleTime;

    public boolean isPending() {
        return status != null && status == RequestStatus.PENDING.getCode();
    }
}
