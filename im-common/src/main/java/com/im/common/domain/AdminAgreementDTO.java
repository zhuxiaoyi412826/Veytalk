package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 协议文本版本在后台侧的档案（{@code im_agreement} 的一行）。
 *
 * <p>一份协议 = 一个 code + 一个 version，<b>发布后不可改正文</b>：已经有人对着这一版点过同意，
 * 改一个字就等于在用户没看到的情况下换掉了合同内容，那份同意留痕立刻变成伪证。
 * 所以后台的编辑只发生在草稿态，改已发布版本这条路根本不存在，要变更就发新版本。
 *
 * <p>{@code content} 只在详情里给（分页时置 null）：三份正文各几 KB 到几十 KB，
 * 列表页一次翻十行就把它们全拉进内存，而列表那一屏连正文的第一个字都不显示。
 *
 * <p>与用户端的 {@code AgreementVO} 刻意分开：这里带着 publishByName、remark、status 这些
 * 内部审计字段，那份是对外公示文本，两者字段会各自漂移，共用一个类迟早变成「为了后台多给一个字段
 * 而把内部字段公示出去」。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminAgreementDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 版本行 ID；新建草稿时为 null */
    private Long id;

    /** 协议标识：privacy 隐私政策 / terms 用户协议 / service 服务条款，见 {@code Agreement.CODE_*} */
    private String code;

    /** 中文展示名，由 code 映射，给列表那一列直接用，省得前端再抄一份字典 */
    private String codeName;

    /** 版本号，同一 code 内单调递增；服务端在建草稿时算好，客户端传来的值一律不认 */
    private Integer version;

    private String title;

    /** 一句话摘要，弹窗与页脚那行小字用 */
    private String summary;

    /** Markdown 全文；分页查询时为 null */
    private String content;

    /** 是否必签：1 注册必须同意（拒签即无法建号） 0 可选服务条款（拒签只关掉对应增值功能） */
    private Integer required;

    /** 状态：0 草稿 1 已发布（同一 code 至多一行） 2 已归档（被新版本顶替，文本仍永久可查） */
    private Integer status;

    /** 生效时刻，发布时盖；与 publishTime 同值，留两列是为了将来允许「今天发、下周一生效」 */
    private LocalDateTime effectiveTime;

    private LocalDateTime publishTime;

    /** 发布人用户 ID */
    private Long publishBy;

    /** 发布人昵称，查不到时退化为「已注销管理员」 */
    private String publishByName;

    /** 本版变更说明：写进留痕，监管问「v3 比 v2 改了什么」时唯一能直接答上的字段 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
