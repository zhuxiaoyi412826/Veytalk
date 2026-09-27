package com.im.ai.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 全网检索响应。
 *
 * <p>整个对象会作为 JSON 存进 Redis（{@code im:web:search:{关键字 MD5}}），
 * 因此必须有无参构造与 setter（Lombok 的 @NoArgsConstructor/@Data 提供），
 * 否则缓存读回来是空对象。
 *
 * <p>{@code results} 为空是正常状态而非错误：外网抓取失败、被限流、无结果三种情况
 * 都收敛成空列表，前端只是不显示「网络」分组，聊天消息的检索结果不受影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "全网检索结果")
public class WebSearchVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "实际参与检索的关键字（已清洗截断）")
    private String keyword;

    @Schema(description = "数据来源标识，便于排查抓取格式变化，如 bing")
    private String provider;

    /**
     * 用同一关键字在浏览器里打开搜索页的地址。
     *
     * <p>由后端用配置的抓取入口拼出来而不是前端写死：前端不知道（也不该知道）
     * 当前用的是哪家引擎，抓取入口改版、换成付费 API 时这个兜底入口跟着配置走。
     */
    @Schema(description = "在浏览器里打开同一关键字的搜索页地址")
    private String moreUrl;

    @Schema(description = "结果列表，按抓取页顺序")
    private List<WebResultVO> results;
}
