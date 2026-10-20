package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 接龙消息里的单条参与者条目（{@code im_message.extra.chainItems} 的元素）。
 *
 * <p>序号不单独存字段——列表顺序即序号（第 1 项就是「1.」），追加即 append 到末尾，
 * 这样「重排 / 删除中间某项」这种会打乱编号的操作根本没有落点，前端也不会出现编号与实际位置不符。
 *
 * <p>{@code userId} 用 {@code Long}：跨端序列化时超 JS 安全整数的雪花值会由全局
 * {@code SafeLongSerializer} 转成字符串，前端比较一律先过 {@code asId()}，与其余消息 ID 同一口径。
 * {@code nickname} 是加入时刻的昵称快照（服务端取，不接受客户端上报），事后改昵称不回溯已接的龙。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "接龙的单条参与者条目")
public class ChainItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "参与者用户 ID（服务端按登录态写入，不接受客户端伪造）")
    private Long userId;

    @Schema(description = "参与者昵称快照（加入时刻定格）")
    private String nickname;

    @Schema(description = "条目正文：本人填写的接龙内容，为空时回落为昵称")
    private String text;

    @Schema(description = "加入时刻")
    private LocalDateTime time;
}
