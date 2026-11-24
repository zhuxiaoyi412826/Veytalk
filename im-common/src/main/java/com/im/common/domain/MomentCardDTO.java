package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 动态卡片快照，{@code MomentQuerySpi#loadCard} 的返回体。
 *
 * <p>它存在的唯一理由是<b>让消息模块不必知道动态的可见性规则</b>：im-message 要把一条动态转成
 * 会话里的卡片，就必须确认「发这条消息的人现在看得到它」，并拿到三个要定格的快照字段。
 * 这两件事都只有 im-friend 能回答（受众 = 好友 ∪ 配对 ∪ 自己 − 我拉黑的人，外加单条的
 * {@code visibility}、白名单与作者的「几天可见」偏好），所以由这条契约代办，
 * 而不是让 im-message 去读 {@code im_moment}。
 *
 * <p><b>不含任何媒体地址</b>：口径见 {@link MessageExtra#getMomentId()} 的注释。
 *
 * <p>不含「看不看得到的原因」：查不到与无权一律返回 {@code null}，由调用方回一个
 * 「这条动态不在了」，分开就等于把动态 ID 的存在性交给外部逐个试。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "动态卡片快照（跨模块）")
public class MomentCardDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "动态 ID")
    private Long momentId;

    @Schema(description = "作者用户 ID")
    private Long authorId;

    @Schema(description = "作者昵称快照")
    private String authorNickname;

    @Schema(description = "正文摘要快照（服务端截断，纯图动态为空）")
    private String summary;

    @Schema(description = "附件条数快照")
    private Integer mediaCount;
}
