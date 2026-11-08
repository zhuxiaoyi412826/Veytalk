package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 「这个账号现在还有哪些协议的当前生效版没同意过」的答复。
 *
 * <p>它同时兜着两种待签，前端的处理动作是一样的（弹窗、读文本、表态），但话术不一样：
 *
 * <ul>
 *   <li><b>文本改版了</b>：他同意过 v1，现在生效的是 v2，{@code latest.version} 与留痕里的不同。
 *       这必须重新取同意——v1 的同意覆盖不了 v2 新增的收集行为。</li>
 *   <li><b>从来没问过</b>：验证码登录自动建号的账号，以及这套功能上线之前就已经注册的老账号。
 *       他们没有勾过任何东西，库里一条留痕都没有。</li>
 * </ul>
 *
 * <p>{@code dismissible} 是「能不能关掉这个弹窗」的服务端答案：只要还有一份必签项没落定，
 * 它就是 false。让前端自己判等于把「必签」这个法务口径抄成两份前端代码，
 * 将来加一份必签协议要改三处。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "待同意协议清单")
public class AgreementPendingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否有待同意项；false 时 agreements 为空，前端不必挂弹窗")
    private Boolean need;

    @Schema(description = "弹窗是否可跳过：存在未同意的必签项时为 false")
    private Boolean dismissible;

    @Schema(description = "待同意项，均为当前生效版；正文为 null，全文由公示页按 code 取")
    private List<AgreementVO> agreements;
}
