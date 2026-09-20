package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话成员的两个推进位点，跨模块传输使用。
 *
 * <p>群聊已读不自建「每条消息 × 每个成员」的回执行（防表爆炸），
 * 「几人已送达 / 几人已读」全部由这两个位点与消息 seq 比较推算：
 * {@code ackSeq >= 消息seq} 即已送达，{@code readSeq >= 消息seq} 即已读。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话成员位点")
public class MemberPositionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "已确认接收位点（离线拉取完成后前进）")
    private Long ackSeq;

    @Schema(description = "已读位点（用户真正打开会话才前进）")
    private Long readSeq;
}
