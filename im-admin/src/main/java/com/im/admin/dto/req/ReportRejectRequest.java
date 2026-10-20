package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 驳回举报工单的入参。
 *
 * <p>为什么单开一个类而不是复用 {@link ReportHandleRequest}：驳回<b>没有动作</b>可选，
 * 复用就会多出一个没人看的 {@code action} 字段，还要在 service 里写「这个接口忽略 action」
 * 这种解释——一个必须被忽略的字段就是接口契约在说谎。
 *
 * <p>{@code remark} 是否必填由 {@code im.admin.handle-remark-required} 决定（默认必填）：
 * 举报人有权知道「为什么不成立」，只回一句「已驳回」和没处理过几乎无法区分。
 */
@Data
@Schema(description = "驳回举报工单入参")
public class ReportRejectRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "驳回理由，会回显给举报人并留档")
    private String remark;
}
