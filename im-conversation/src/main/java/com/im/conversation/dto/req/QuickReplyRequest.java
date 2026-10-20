package com.im.conversation.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 常用语（快捷回复）的<b>整表替换</b>入参。
 *
 * <p>只有 PUT 没有 PATCH：面板里编辑的就是一整份清单，增删改每一条都要发一次请求
 * 并不比整体重写一次便宜，反而要在服务端为「顺序」多维护一套插入语义。顺序就是
 * 数组顺序——用户把哪条排在前面，聊天面板上就先看到哪条。
 *
 * <p>{@code @NotNull} 而非允许 null：清空必须显式传空数组，避免「字段漏传」被当成清空。
 */
@Data
@Schema(description = "常用语整表替换入参")
public class QuickReplyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "常用语列表，传空数组表示清空", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "常用语列表不能为 null（清空请传空数组）")
    private List<String> replies;
}
