package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条「谁在什么时候对哪一版协议点了同意 / 拒绝」的留痕（{@code im_user_agreement} 的一行）。
 *
 * <p>这一屏是<b>举证材料</b>，不是运营数据：《网络安全法》与个保法要的不是「我们挂过文本」，
 * 而是「这个人在这个时候对这一版文本点过头，而且我们拿得出来」。所以三样东西必须同时在场——
 * 谁（userId + 当时的账号昵称）、什么时候（consentTime + ip + userAgent）、哪一版
 * （agreementId 精确到行，code + version 冗余一份快照）。
 *
 * <p>code 与 version 是从 {@code im_agreement} 抄过来的<b>冗余快照</b>，不是偷懒：
 * 留痕表要能被单独导出、单独归档，而协议文本表归法务管、随时可能改版改名。
 * 真按外键去 join，将来那份导出件就会因为主表一行没落而缺一段，取证现场最不能容忍的就是这种缺。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAgreementConsentDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    /** 同意发生时的登录账号；用户后来改过昵称也不影响这里读到的历史值 */
    private String account;

    /** 同意发生时的昵称，为空时前端退化成 account */
    private String nickname;

    /** 具体版本行 ID，点进去能看到他当时同意的那份原文 */
    private Long agreementId;

    /** 协议标识快照 */
    private String code;

    /** 协议中文展示名，由 code 映射 */
    private String codeName;

    /** 版本号快照 */
    private Integer version;

    /** 该版本的标题，从主表现查（主表标题不可改，所以查到的就是当时那个） */
    private String title;

    /** 动作：1 同意 0 拒绝（拒绝同样要留痕——「明确拒过」本身就是他后来没被强制开通增值服务的证据） */
    private Integer action;

    /** 同意场景：register 注册勾选 / login 验证码登录自动建号 / dialog 登录后强制再同意弹窗 */
    private String source;

    /** 来源 IP，已经过与日志同口径的反代头解析 */
    private String ip;

    /** User-Agent，截断存留；判「是不是本人手机点的」时唯一线索 */
    private String userAgent;

    /** 同意时刻 */
    private LocalDateTime consentTime;
}
