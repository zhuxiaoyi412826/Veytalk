package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 图片审核的单个类别评分（五期·路线 2），模型输出原样留痕。
 *
 * <p>与 im-file 侧的 {@code ModerationReportReq.Score} 字段同名但职责不同：
 * 那个是客户端上报的入参（不采信），这个是清洗后落库再交给后台渲染的结果。
 * 跨模块契约里不 import 业务模块的类，所以公共层单独有这一份。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModerationScoreDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 类别名（模型输出原名）：Porn / Sexy / Neutral ... */
    private String className;

    /** 概率（0~1，写入时已夹） */
    private Double probability;
}
