package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量导入敏感词的结果计数。
 *
 * <p>四个数字必须分开给，不能只回一句「导入成功」：管理员一次贴进来几百个词，其中
 * 有一半是文件词库里已有的（重复）或单字／带符号的（不合法），只报一个「成功」他无从知道
 * 词表实际长了多少。{@code added} 与 {@code total} 的差值就是这批里被丢掉的部分，
 * 具体丢在哪一类由后两个字段说清。
 *
 * <p>这里不做「哪几个词被丢了」的清单：几百条里挑出十条，一份字符串列表在页面上远不如
 * 一句「非法 10 条，格式要求：至少 2 个字符且只含汉字与小写字母数字」有用。
 */
@Data
@Schema(description = "敏感词批量导入结果")
public class SensitiveWordImportVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "拆出的词条总数（含被丢弃的）")
    private Integer total;

    @Schema(description = "实际入库的条数")
    private Integer added;

    @Schema(description = "词库里已存在的条数（含全角与大小写变体）")
    private Integer duplicated;

    @Schema(description = "格式不合法的条数（单字、带标点或符号、含正则元字符等）")
    private Integer invalid;

    @Schema(description = "导入后字典树的生效词条总数（文件词库 + 后台词库合并去重）")
    private Integer effectiveCount;
}
