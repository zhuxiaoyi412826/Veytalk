package com.im.ai.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单条全网检索结果。
 *
 * <p>刻意不带 messageId / conversationId 等 IM 字段：网络结果不属于任何会话，
 * 前端渲染时也不能走「点开跳到会话」那条路径，字段形状必须和消息搜索结果区分开。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "全网检索结果条目")
public class WebResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "网页标题")
    private String title;

    @Schema(description = "网页地址，前端在新标签页打开")
    private String url;

    @Schema(description = "来源站点域名，如 csdn.net")
    private String site;

    @Schema(description = "摘要片段（已去标签并截断）")
    private String snippet;
}
