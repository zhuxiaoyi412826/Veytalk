package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 给用户整体重设角色的入参。
 *
 * <p>{@code roleIds} 是<b>整体替换</b>语义而不是「追加」：传空集就是把该用户的所有后台角色清空，
 * 传 {@code [1]} 就是只留 admin。之所以不做增量添加/删除两个接口，是因为「当前应有哪些角色」这件事
 * 只有一个权威答案，界面把复选框的最终状态一次性提交回来最不容易漂移——增量接口一旦漏发一次
 * 「移除」，用户就会带着一个他本不该有的角色长期留在后台里。
 */
@Data
@Schema(description = "用户角色分配入参")
public class UserRoleAssignRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "roleIds 不能为 null（清空角色请显式传空数组）")
    @Schema(description = "期望的角色主键集合，整体替换该用户现有角色；空数组表示清空全部角色")
    private List<Long> roleIds;
}
