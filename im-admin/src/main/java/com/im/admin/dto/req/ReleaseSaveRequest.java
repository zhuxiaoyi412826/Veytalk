package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 客户端版本新建 / 编辑入参。
 *
 * <p>{@code fileId} 必填且必须先行上传得到：安装包字节走文件模块的分片上传通道
 * （2GB 上限、MD5 秒传、断点续传），本接口只登记「哪个文件是哪一个版本」，
 * 不收任何文件字节——于是后台控制器不需要为安装包单独放宽 multipart 上限。
 *
 * <p>编辑时 {@code platform} 不参与修改（传了也被忽略）：平台是启用互斥的分组键，
 * 改平台等于把一行搬去另一个互斥域，语义上就是「删旧建新」，不该由一个 PUT 偷偷完成。
 */
@Data
@Schema(description = "客户端版本保存入参")
public class ReleaseSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "平台码，取值见 ReleasePlatform；仅新建时生效", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "windows_setup")
    @NotBlank(message = "平台不能为空")
    private String platform;

    @Schema(description = "版本号，最多 32 字符", requiredMode = Schema.RequiredMode.REQUIRED, example = "1.0.1")
    @NotBlank(message = "版本号不能为空")
    @Size(max = 32, message = "版本号最多 32 个字符")
    private String version;

    @Schema(description = "更新说明，最多 500 字，可留空", example = "修复弱网下消息重发")
    @Size(max = 500, message = "更新说明最多 500 字")
    private String releaseNote;

    @Schema(description = "安装包文件 ID（先经分片上传拿到）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1900000000000000001")
    @NotNull(message = "请先上传安装包")
    private Long fileId;

    @Schema(description = "新建后是否立即启用（同平台原启用行会被自动停用）；编辑时忽略", example = "false")
    private Boolean enableNow;
}
