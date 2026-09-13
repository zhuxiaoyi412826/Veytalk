package com.im.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.im.common.domain.MessageExtra;
import com.im.common.enums.MsgType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息实体，对应 {@code im_message}。
 *
 * <p>刻意不继承 {@code AuditEntity} / {@code BaseEntity}：消息表只有 {@code create_time}，
 * 既没有 {@code update_time} 也没有 {@code deleted}——消息一旦发出就不允许被物理修改或删除，
 * 撤回是打标记，单端删除记在 {@code im_message_delete}。若继承了带 {@code update_time} 的基类，
 * MyBatis-Plus 的自动填充会往不存在的列上写值，直接导致 SQL 报错。
 *
 * <p>{@code autoResultMap = true} 是 {@code extra} JSON 列能被 TypeHandler 反序列化的前提，
 * 缺了它查询回来只会得到原始字符串。
 */
@Data
@TableName(value = "im_message", autoResultMap = true)
public class Message implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 客户端消息 ID，与 fromUserId 一起构成幂等键 uk_from_client */
    private String clientMsgId;

    private Long conversationId;

    /** 发送者 ID，{@code ImConstants.SYSTEM_USER_ID}（0）表示系统通知 */
    private Long fromUserId;

    private Integer msgType;

    /** 文本消息为正文，附件类消息为文件 ID 的字符串形式 */
    private String content;

    /** 扩展信息，以 JSON 列存储 */
    @TableField(value = "extra", typeHandler = Jackson3TypeHandler.class)
    private MessageExtra extra;

    /** 会话内自增序列，游标分页与离线消息计算的依据 */
    private Long seq;

    /**
     * 是否已撤回：1 是 0 否。
     *
     * <p>属性名不带 {@code is} 前缀，避免 Lombok 与 MyBatis-Plus 对 {@code isXxx} 的 getter 推导产生歧义，
     * 列名通过 {@code @TableField} 显式绑定。
     */
    @TableField("is_recalled")
    private Integer recalled;

    private LocalDateTime recallTime;

    /** 发送时间，取服务端落库时刻，客户端时钟不参与排序 */
    private LocalDateTime sendTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public boolean isRecalledNow() {
        return recalled != null && recalled == 1;
    }

    public boolean isSystem() {
        return MsgType.SYSTEM.getCode() == (msgType == null ? -1 : msgType);
    }
}
