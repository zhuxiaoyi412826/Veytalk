package com.im.admin.dto.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 一个版本脚本的原文（页面「点击查看实际执行的 SQL」弹窗数据）。
 * 只读展示用：内容原样返回，不做语句切分——切分是 apply/rollback 时服务端的事。
 */
@Data
@Builder
public class SchemaScriptVO {

    private String version;

    /** 实际读取的文件名（由目录扫描按版本号解析，非客户端传入，天然免疫路径穿越） */
    private String scriptName;

    /** 是否回滚脚本（true=U 文件，false=V 文件） */
    private Boolean undo;

    /** UTF-8 全文 */
    private String content;
}
