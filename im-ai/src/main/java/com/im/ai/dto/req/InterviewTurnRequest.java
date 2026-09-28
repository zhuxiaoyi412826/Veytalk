package com.im.ai.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 一轮问答落库请求。
 *
 * <p>由前端在 SSE 的 done 事件之后一次性把「候选人这一答 + 面试官紧接着的新题」送过来：
 * 对话历史本来就握在前端手里，服务端重新拼一遍只会得到一份可能不一致的副本。
 *
 * <p>两个字段都为空则报错，只有一个非空是正常情况：
 * 开场那一问没有上一答，结尾的总结也一样。
 *
 * <p>{@code turnNo} 是幂等键的一部分：服务端拿它而不是自算的 seq 去撞
 * {@code uk_session_turn}，前端重试才不会多插一行。
 */
@Data
@Schema(description = "AI 面试问答落库")
public class InterviewTurnRequest {

    @NotNull(message = "面试会话 ID 不能为空")
    @Schema(description = "面试会话 ID")
    private Long sessionId;

    /** 提问与作答至少有一个非空，这一条在 Service 里校——注解只能管住单字段，管不住「两个都为空」 */
    @Schema(description = "面试官本轮出的新题（或结尾总结），可为空串")
    private String question;

    @Schema(description = "候选人对上一题的作答，可为空串（开场与结尾时没有）")
    private String answer;

    /** 不强制必填：老前端不传时服务端按当前轮数推算，代价只是丢掉幂等 */
    @Min(value = 1, message = "轮号从 1 开始")
    @Schema(description = "本轮新题的题号（开场那一问为 1）；同一轮重试必须带同一个值", example = "3")
    private Integer turnNo;

    @Schema(description = "候选人本题作答耗时（毫秒）")
    private Long elapsedMs;
}
