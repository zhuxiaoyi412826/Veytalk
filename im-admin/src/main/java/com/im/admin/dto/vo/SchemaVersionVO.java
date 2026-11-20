package com.im.admin.dto.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 「SQL 版本管理」页的一行：台账表行与 sql/versions 目录里的脚本文件合并后的视图。
 *
 * <p>三种来源都要能表达，所以一半字段是对象而不是基本类型：
 * 只在目录里（待应用，没有台账行）、只在表里（历史基线或脚本被删）、两边都有（常态）。
 * 时间统一在后台格式化成字符串，前端拿到就是 {@code yyyy-MM-dd HH:mm:ss}，不再猜序列化格式。
 */
@Data
@Builder
public class SchemaVersionVO {

    private String version;

    /** 向前脚本文件名；历史基线行是说明文本 */
    private String scriptName;

    /** 回滚脚本文件名；null 表示不可一键回滚 */
    private String undoName;

    private String description;

    /** 1 已应用 / 0 已回滚 / 2 应用失败 / null 待应用（还没有台账行） */
    private Integer status;

    /** 状态的中文口径（待应用 / 已应用 / 已回滚 / 应用失败 / 历史基线…），页面直接展示 */
    private String statusText;

    /** 台账指向的 V 脚本是否还在目录里（历史基线行为 true 的语义是"无需文件"，见 statusText） */
    private Boolean fileFound;

    /** 重算文件 SHA-256 与台账登记值是否一致；null 表示无从比对（基线/待应用） */
    private Boolean checksumMatched;

    /** 最近一次执行人昵称快照 */
    private String operatorName;

    private String appliedAt;

    private String rolledBackAt;

    /** 可应用：文件在、台账非已应用、且它是版本号最小的待应用项（脚本有依赖，强制升序） */
    private Boolean canApply;

    /** 可回滚：已应用 + 配了 U 脚本 + 是最近一次应用（LIFO）。前端只按这个布尔画按钮，规则都在服务端 */
    private Boolean canRollback;
}
