package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台加敏感词入参（单条与批量共用这一个类）。
 *
 * <p>单条与批量<b>共用这一个字段</b>：允许顿号 / 中英文逗号 / 换行分隔，拆出一个就是一个、
 * 拆出多个就是批量，所以服务端只有一份入库路径，不存在「单条能加、批量加不上」这种口径差。
 * 真正的格式闸门在 {@code SensitiveWordFilter.isValidWord} 上，这里只做「非空」
 * 与「别把整个磁盘贴进来」两层。
 *
 * <p>{@code 20000} 这个上界是给粘贴用的余量：一万九千条的整份文件约一百多 KB，本来就超了，
 * 而后台的正确用法是贴一小批新词。真要把整份文件换掉，那应该改代码里的词库资源重新打包
 * （在线词库是<b>增量</b>，不是替代，理由见 {@code SensitiveWord} 实体注释）。
 */
@Data
@Schema(description = "后台新增敏感词入参")
public class SensitiveWordAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "词条，单条词或按顿号/逗号/换行分隔的一批词",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "测试违禁词")
    @NotBlank(message = "词条不能为空")
    @Size(max = 20000, message = "一次提交的词条内容不能超过 20000 字符，请分批提交")
    private String word;

    @Schema(description = "加词原因，可选。词表没有审计表兜底，这是唯一能问出「为什么加它」的地方")
    @Size(max = 200, message = "加词原因不能超过 200 字符")
    private String remark;
}
