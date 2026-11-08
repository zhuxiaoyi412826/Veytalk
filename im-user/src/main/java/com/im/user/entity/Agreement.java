package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 对外公示的法定文本（一个版本一行），对应 {@code im_agreement}。
 *
 * <p><b>本表刻意不继承 {@code BaseEntity}（没有 {@code deleted} 列）</b>：协议文本是「我们对用户
 * 承诺过什么」的原件，删除一条已发布文本等于销毁合同原本。法务要改文案只能发新版本、旧版本转
 * {@link #STATUS_ARCHIVED} 归档，而行永远留在库里——任何人（包括已经注销的账号当初看到的链接）
 * 按 {@code code + version} 都还能查到那份原文。后台因此根本没有删除端点。
 *
 * <p>状态是一条单向线（{@link #STATUS_DRAFT} → {@link #STATUS_PUBLISHED} → {@link #STATUS_ARCHIVED}）：
 * 同一个 code 至多一行已发布，发布动作把前一份顶成归档。没有「撤回」这条边。
 *
 * <p>{@code version} 由服务端在同一 code 内取「当前最大值 + 1」，客户端传来的值一律不认：
 * 它同时是同意留痕里的快照字段，能被调用方改写就等于允许留痕对不上账。
 *
 * <p>{@code content} 是 Markdown 原文，与文章正文同一渲染口径（前端 {@code MarkdownView} 走
 * marked + DOMPurify）。法定文本里有表格（收集了哪些个人信息）与多级标题，纯文本撑不住，
 * 而 HTML 原文又要把 XSS 面交给后台编辑者，所以取中间那个：受限 Markdown 存原文，渲染端净化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_agreement")
public class Agreement extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 协议标识：隐私政策（必签） */
    public static final String CODE_PRIVACY = "privacy";
    /** 协议标识：用户协议（必签） */
    public static final String CODE_TERMS = "terms";
    /** 协议标识：服务条款（可选，覆盖钱包 / 直播打赏 / 付费通话等增值服务） */
    public static final String CODE_SERVICE = "service";

    /** code → 中文展示名。给后台列表与前端标题位用，字典只有这一处 */
    private static final Map<String, String> CODE_NAMES = Map.of(
            CODE_PRIVACY, "隐私政策",
            CODE_TERMS, "用户协议",
            CODE_SERVICE, "服务条款");

    /** 状态：草稿，仅后台可见，可继续编辑 */
    public static final int STATUS_DRAFT = 0;
    /** 状态：已发布，对外生效中；同一 code 至多一行处于此态 */
    public static final int STATUS_PUBLISHED = 1;
    /** 状态：已归档，被新版本顶替；文本仍永久可查，只是不再是「当前生效版」 */
    public static final int STATUS_ARCHIVED = 2;

    /** 必签 */
    public static final int REQUIRED_YES = 1;
    /** 可选（用户可明确拒绝，服务端留一条 action=0 的痕） */
    public static final int REQUIRED_NO = 0;

    /** code 白名单：注册请求与后台入参都按这个集合校验，不在集合内的一律拒 */
    public static boolean isKnownCode(String code) {
        return code != null && CODE_NAMES.containsKey(code);
    }

    /** 展示名；未知 code 原样返回，让页面显示一个看得懂的错字而不是一句「名称缺失」 */
    public static String nameOf(String code) {
        String name = code == null ? null : CODE_NAMES.get(code);
        return name == null ? code : name;
    }

    /** 全部已知 code，按「隐私政策、用户协议、服务条款」这个法务习惯顺序 */
    public static String[] allCodes() {
        return new String[]{CODE_TERMS, CODE_PRIVACY, CODE_SERVICE};
    }

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 协议标识，见 {@code CODE_*} */
    private String code;

    /** 同一 code 内单调递增的版本号 */
    private Integer version;

    /** 文档标题，公示页的 h1 */
    private String title;

    /** 一句话摘要，弹窗与页脚那行小字 */
    private String summary;

    /** Markdown 正文原文 */
    private String content;

    /** 是否必签，见 {@code REQUIRED_*} */
    private Integer required;

    /** 状态，见 {@code STATUS_*} */
    private Integer status;

    /** 生效时刻，发布时盖 */
    private LocalDateTime effectiveTime;

    /** 发布时刻 */
    private LocalDateTime publishTime;

    /** 发布人（后台账号 ID） */
    private Long publishBy;

    /** 本版变更说明：「v3 比 v2 改了什么」唯一能直接答上的字段 */
    private String remark;

    public boolean published() {
        return status != null && status == STATUS_PUBLISHED;
    }

    public boolean draft() {
        return status != null && status == STATUS_DRAFT;
    }

    public boolean mandatory() {
        return required != null && required == REQUIRED_YES;
    }
}
