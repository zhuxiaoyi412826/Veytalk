package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 合并转发（聊天记录）里的单条快照项。
 *
 * <p>发送合并转发时由前端把所选消息折算成「谁说的 + 一句摘要 + 时间」定格下来随消息一起发出，
 * 服务端只做清洗与限长、不重新回表取原文（合并的是发送者此刻可见的视图）。
 * 接收端展开查看器时直接渲染这些快照，不需要再按原消息 ID 逐条回查。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "合并转发的单条快照项")
public class MergedMessageItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "发言人昵称快照")
    private String nickname;

    @Schema(description = "原消息类型码，供查看器区分文本/图片/文件占位")
    private Integer msgType;

    @Schema(description = "内容摘要：文本为正文，附件为 [图片]/[文件] 之类占位")
    private String summary;

    @Schema(description = "原发送时间（ISO 字符串），查看器按本地格式展示")
    private String time;
}
