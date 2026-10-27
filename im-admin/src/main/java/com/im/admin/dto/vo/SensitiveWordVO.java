package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台词库里的一条敏感词。
 *
 * <p>{@code word} 回显的是<b>折叠后</b>的入库形式（全角已转半角、已统一小写）。必须如实回显：
 * 管理员贴进 {@code ＡＢＣ}、界面却显示成没变过的原样，他会以为存了两条，而实际只有一条且匹配键是
 * {@code abc}。这一列显示什么，字典树里的键就是什么。
 */
@Data
@Schema(description = "后台敏感词")
public class SensitiveWordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "词条 ID")
    private Long id;

    @Schema(description = "词条（已折叠：全角转半角 + 小写）")
    private String word;

    @Schema(description = "加词原因")
    private String remark;

    @Schema(description = "创建人（后台账号 ID）")
    private Long createBy;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
