package com.im.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 某账号的 AI 功能封禁状态（AI 护栏 · 四期第六批）。
 *
 * <p>一行一人，主键即 {@code userId}（{@code IdType.INPUT}：ID 是业务键不是雪花）。
 * 没有封禁过的人<b>根本没有这一行</b>而不是有一行 banned=0——护栏每次调用查一次
 * {@code selectById}，查不到就是没封，零成本走最短路径。
 *
 * <p>封禁只停「调用付费模型」这一项能力，不动账号登录；解除即恢复。
 * {@code updateBy} 记下处置人，与后台其它写动作同一问责口径。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_ai_guard")
public class AiGuard extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 被管控的用户 ID，同时是主键 */
    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 是否封禁：0 未封禁 1 已封禁 */
    private Integer banned;

    /** 封禁原因（展示给被封者与管理台） */
    private String reason;

    /** 最后一次封/解操作的后台操作人 ID */
    private Long updateBy;
}
