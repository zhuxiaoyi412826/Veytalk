package com.im.ai.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 面试对话请求。
 *
 * <p>后端不保存会话：完整对话历史由前端持有并每次带上（无状态设计，
 * 多端打开面试页互不干扰，服务重启也不丢会话）。服务端只做
 * 裁剪（条数/长度上限）、检索增强与转发，见 {@code InterviewService}。
 */
@Data
@Schema(description = "AI 面试对话请求")
public class InterviewChatRequest {

    /**
     * 对话历史，按时间升序，最后一条必须是 user。
     * 空列表表示「开始新面试」，由面试官先出阶段 1 的开场问题。
     */
    @Valid
    @Size(max = 100, message = "对话历史过长，请重新开始面试")
    @Schema(description = "对话历史（role=user/assistant），空数组表示开始新面试")
    private List<Message> messages;

    @Data
    @Schema(description = "单条对话消息")
    public static class Message {

        @NotBlank(message = "消息角色不能为空")
        @Pattern(regexp = "user|assistant", message = "消息角色只能是 user 或 assistant")
        @Schema(description = "角色：user=候选人，assistant=面试官")
        private String role;

        @NotBlank(message = "消息内容不能为空")
        @Schema(description = "消息文本")
        private String content;
    }
}
