package com.im.ai.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 语音合成（TTS）请求。
 *
 * <p>上限 800 字：面试官的单轮回答正常在 300 字以内，留出总结陈词的余量；
 * 再长意味着前端逻辑跑偏（比如把整段历史灌进来说话），宁可拦下也不让
 * 一次误用吃掉大段神经音色的 token 计费。
 */
@Data
@Schema(description = "面试语音合成")
public class InterviewTtsRequest {

    @NotBlank(message = "朗读内容不能为空")
    @Size(max = 800, message = "朗读内容不能超过 800 字")
    @Schema(description = "要逐字朗读的文本（面试官回答原文）")
    private String text;
}
