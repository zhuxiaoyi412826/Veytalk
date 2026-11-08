package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对外公示的协议文本。
 *
 * <p>这个类的每一个字段都是<b>公开</b>的：它挂在匿名接口上，没登录的人也能读。
 * 所以后台用的那套内部审计字段（发布人、变更说明、状态）一律不往这里放——
 * 「这份文本处于草稿态」这种事对外没有意义，「谁发布的」更不是。
 *
 * <p>{@code content} 在列表接口里为 null：注册页与页脚只需要三个链接和版本号，
 * 把三份全文一次性塞进一个响应里，等于让每个打开登录页的人都先下载几十 KB 才不会看的文本。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "协议文本")
public class AgreementVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "协议标识", example = "privacy")
    private String code;

    @Schema(description = "中文展示名", example = "隐私政策")
    private String codeName;

    @Schema(description = "当前生效版本号，同意时原样回传", example = "1")
    private Integer version;

    @Schema(description = "文档标题")
    private String title;

    @Schema(description = "一句话摘要")
    private String summary;

    @Schema(description = "Markdown 正文；列表接口为 null")
    private String content;

    @Schema(description = "是否必签：true 注册必须同意，false 可拒（仅影响对应增值服务）")
    private Boolean required;

    @Schema(description = "生效时间，公示页那行「本版生效于」")
    private LocalDateTime effectiveTime;
}
