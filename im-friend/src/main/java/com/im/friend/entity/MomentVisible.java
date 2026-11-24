package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 「部分人可见」白名单的一行（{@code im_moment_visible}）：这条动态允许被这个人看到。
 *
 * <p><b>没有 {@code deleted} 列</b>，所以继承 {@link AuditEntity}：改可见范围时整批重写
 * （先物理删光再插新的），逻辑删会让同一批人反复插入时撞 {@code uk_moment_user}，
 * 而「谁被允许看过」这件事没有需要保留的历史——动态本身留着就够了。
 *
 * <p>只在 {@code visibility = 3} 时才有行。其余三档的判据全在关系侧（好友 / 配对 / 作者本人），
 * 为它们也写白名单就等于把关系表复制一份，而两份的关系一旦不同步，多出来的那一份永远是「多给了」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_moment_visible")
public class MomentVisible extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long momentId;

    /** 被允许看到这条动态的用户 ID */
    private Long userId;
}
