package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 一台登录过的设备，对应表 {@code im_user_device}（一台实例一行）。
 *
 * <p>「设备位」在本表里被拆成 {@code deviceType + deviceId} 两列存：代码里到处传的是拼好的
 * {@code 类型:实例}（见 {@code DeviceIds}），而库里拆开才能做「只看手机端」这类筛选，
 * 也让 {@code uk_user_device} 直接把「一台机器一行」这条不变量交给数据库保证。
 *
 * <p>{@code deviceId} 为<b>空串</b>而不是 null：MySQL 唯一索引里 null 与 null 不算重复，
 * 可空的话老客户端（只上送类型码）同一类型能堆进任意多行，唯一键形同虚设。
 * 空串行同时意味着「这台机器无法被单独定位」，因此它<b>不参与顶号</b>——
 * 拿类型码顶号会把同一浏览器的多个标签页互相挤下线，那正是当年 {@code kickSameDevice} 被删的原因。
 *
 * <p>继承 {@link AuditEntity} 而不带 {@code deleted} 列：撤销设备是改 {@link #status}，
 * 不是删行——一台机器「曾经登录过」是账号安全的留痕，删掉就查无此事了。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user_device")
public class UserDevice extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：在用 */
    public static final int STATUS_ACTIVE = 1;
    /** 状态：已被用户撤销（既有会话已注销；凭密码重新登录会回到 {@link #STATUS_ACTIVE}） */
    public static final int STATUS_REVOKED = 0;

    /** 老客户端只上送类型码时，实例列落这个空串（唯一键要求 NOT NULL） */
    public static final String NO_INSTANCE = "";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 设备类型码：web / pc / android / ios / mini，取值见 {@code DeviceType} */
    private String deviceType;

    /** 安装实例 ID（客户端首启生成的 UUID）；空串表示老客户端没上送 */
    private String deviceId;

    /** 客户端自报的友好名，仅用于展示与被踢提示，不参与任何判定 */
    private String deviceName;

    /** 最近一次登录的客户端 IP（未接归属地库，所以只有 IP，没有城市） */
    private String ip;

    /** 最近一次登录的 User-Agent，{@code deviceName} 缺失时靠它兜底推设备名 */
    private String userAgent;

    /** 该实例累计登录成功次数 */
    private Integer loginCount;

    private LocalDateTime lastLoginTime;

    private LocalDateTime lastLogoutTime;

    /** 1 在用 / 0 已撤销，见 {@link #STATUS_ACTIVE} */
    private Integer status;
}
