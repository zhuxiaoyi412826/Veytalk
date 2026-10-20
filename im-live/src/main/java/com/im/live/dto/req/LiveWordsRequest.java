package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 主播弹幕屏蔽词的<b>整表替换</b>请求。
 *
 * <p>只有 PUT 没有 PATCH：词表是主播在自己面板里编辑的一整份清单，
 * 增删各做一个接口要多一次「先读后写」的往返，还容易在服务端留下并发窗口。
 * 传空列表即清空。
 */
@Data
@Schema(description = "弹幕屏蔽词整表替换请求")
public class LiveWordsRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "屏蔽词列表，传空表示清空", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "屏蔽词列表不能为 null（清空请传空数组）")
    private List<String> words;
}
