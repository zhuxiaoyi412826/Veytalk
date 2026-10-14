package com.im.file.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 图片内容审核策略，下发给发送端浏览器。
 *
 * <p>阈值放在服务端而不是硬编码进前端：调阈值是运营动作，不该要求用户重新下载前端包，
 * 也不该让多端（web / Electron / 安卓壳）各存一份可能不一致的副本。
 * 前端拿到后就地把 {@code enabled=false} 当作「整个识别环节跳过」，
 * 拉取失败时用内置的同名默认值兜底——拦不住新阈值，但不至于因为策略接口抖动就不发图。
 */
@Data
@Schema(description = "图片内容审核策略")
public class ModerationPolicyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "总开关：false 时前端不做任何识别，直接照常上传")
    private boolean enabled;

    @Schema(description = "命中后是否要求上报审计；false 时前端只拦不报")
    private boolean auditEnabled;

    @Schema(description = "判违规的类别与阈值：类别名 → 概率上限，任一类命中即拒发；未列出的类别不参与判定")
    private Map<String, Double> thresholds;
}
