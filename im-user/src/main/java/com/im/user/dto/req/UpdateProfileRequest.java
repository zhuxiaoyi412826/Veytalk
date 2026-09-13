package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改个人资料入参，字段为 {@code null} 表示不修改。
 */
@Data
@Schema(description = "修改个人资料请求")
public class UpdateProfileRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "昵称", example = "爱丽丝")
    @Size(max = 32, message = "昵称最长 32 个字符")
    private String nickname;

    @Schema(description = "头像访问地址，由 /api/file/avatar 上传后回填")
    @Size(max = 512, message = "头像地址过长")
    private String avatar;

    @Schema(description = "性别：0 未知 1 男 2 女", example = "2")
    @Min(value = 0, message = "性别取值只能为 0/1/2")
    @Max(value = 2, message = "性别取值只能为 0/1/2")
    private Integer gender;

    @Schema(description = "个性签名", example = "今天也要元气满满呀")
    @Size(max = 255, message = "签名最长 255 个字符")
    private String signature;

    @Schema(description = "邮箱", example = "alice@im.local")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长 64 个字符")
    private String email;
}
