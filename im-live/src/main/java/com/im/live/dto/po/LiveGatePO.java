package com.im.live.dto.po;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 直播间门禁配置，以 JSON 存在 {@code im:live:gate:{roomId}} 里。
 *
 * <p><b>零 DDL</b>：门禁是「这一场直播」的瞬时属性（下一场可以换密码、改价、甚至不再设门槛），
 * 与 PK / 房管 / 回放同构，只存 Redis、不给 {@code im_live_room} 加列。键 TTL 随直播心跳续期，
 * 与直播同生命周期——直播一停、心跳不再续，门禁配置自然消失。
 *
 * <p>{@code pwdHash} 存的是密码的 SHA-256 十六进制摘要，<b>绝不存明文</b>；它也不随任何
 * 详情/列表接口下发（前端只拿到 {@code mode} 与 {@code price}）。密码无盐是有意为之：房间密码由
 * 主播临时设定、观众输入即验证，属于「低价值、可随意改」的门锁而非账户口令，加盐反而要把盐一并存进 Redis。
 *
 * <p>{@code ignoreUnknown}：与回放 JSON 同理，跨版本部署时旧键字段可能多/少，读崩会把一间
 * 明明开着的门禁房显示成「无门禁」直接放人进去，比读崩更糟。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LiveGatePO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 密码房 */
    public static final String MODE_PASSWORD = "password";
    /** 付费房 */
    public static final String MODE_PAID = "paid";

    /** 属于哪一场直播（im_live_room.id） */
    private Long roomId;

    /** 设置门禁的主播 id：仅日志追人用，权限/免扣判定一律现查房间行 */
    private Long anchorId;

    /** 门禁模式：password / paid */
    private String mode;

    /** 密码的 SHA-256 摘要（十六进制小写）；付费房为 null */
    private String pwdHash;

    /** 入场积分价；密码房为 null */
    private Long price;
}
