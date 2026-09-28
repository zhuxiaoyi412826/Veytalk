package com.im.ai.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 监考事件批量上报请求。
 *
 * <p>攒一批再发而不是每个事件一次请求：切屏这类事件在用户疯狂切标签页时是成串到达的，
 * 逐条上报等于把「作弊行为」变成对服务端的 DDoS。前端按时间窗 flush，
 * 页面隐藏（{@code pagehide}）时立刻强制 flush 一次，否则最后一批正好丢在离开的那一下。
 *
 * <p>{@code eventTime} 由客户端给：同一批里的事件在服务端是同一毫秒落库的，
 * 没有客户端时间就分不清先后（也不知道「他离开的那 30 秒里有没有作答」）。
 */
@Data
@Schema(description = "AI 面试监考事件批量上报")
public class InterviewEventReportRequest {

    @NotNull(message = "面试会话 ID 不能为空")
    @Schema(description = "面试会话 ID")
    private Long sessionId;

    @Valid
    @NotNull(message = "事件列表不能为空")
    @Size(max = 100, message = "单次上报的事件过多")
    @Schema(description = "事件列表，按发生时间升序")
    private List<EventItem> events;

    @Data
    @Schema(description = "单条监考事件")
    public static class EventItem {

        /**
         * 事件类型。取值在 Service 侧白名单校验：这里不加 @Pattern，
         * 因为白名单要跟配置开关一起维护，写两遍必然对不上。
         */
        @NotBlank(message = "事件类型不能为空")
        @Size(max = 32, message = "事件类型过长")
        @Schema(description = "事件类型：visibility-hidden/blur/copy/cut/paste/contextmenu/fullscreen-exit")
        private String eventType;

        @Schema(description = "事件发生时刻（客户端本地时间，带毫秒）；不传则按服务端收到时刻记")
        private LocalDateTime eventTime;

        @Schema(description = "当时进行到第几轮，不传按 0")
        private Integer turnNo;

        @Size(max = 500, message = "事件详情过长")
        @Schema(description = "补充信息：离开多久、粘贴多少字符等")
        private String detail;
    }
}
