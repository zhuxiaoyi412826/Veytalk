package com.im.admin.service;

import com.im.admin.dto.vo.AdminRoleVO;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminRoleDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminUserSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 后台角色分配：列出可分配角色、查某用户现有角色、给用户整体重设角色。
 *
 * <p>本类<b>一行角色数据都不直接读</b>，全部经 {@code AdminUserSpi} 交给 im-user 执行——
 * {@code im_role}/{@code im_user_role} 是用户模块的表，星型架构下只在归属模块映射一次。
 * 这里只做后台这一侧的三件事：把 SPI 的 {@code AdminRoleDTO} 转成 REST 的 {@code AdminRoleVO}、
 * 把「角色主键集合」拼成「角色对象集合」给界面回显、以及留一条操作日志。
 *
 * <p>三条硬护栏（改自己角色、摘掉最后一个可用管理员、写入未知/停用角色）都写在
 * {@code AdminUserSpiImpl} 里而不是这一层：护栏的意义是「无论谁调用都不能越过」，
 * 放在调用方等于只对这一个入口生效。这里信任 SPI 的返回值、不重复校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRoleService {

    private final ObjectProvider<AdminUserSpi> adminUserSpiProvider;

    /**
     * 可分配的角色列表（下拉数据源）。
     */
    public List<AdminRoleVO> listRoles() {
        return requireSpi().listRoles().stream().map(this::toVO).toList();
    }

    /**
     * 某用户当前持有的角色（对象列表，界面据此勾选复选框）。
     */
    public List<AdminRoleVO> userRoles(Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
        AdminUserSpi spi = requireSpi();
        List<Long> owned = spi.listUserRoleIds(userId);
        if (owned == null || owned.isEmpty()) {
            return List.of();
        }
        Map<Long, AdminRoleDTO> byId = spi.listRoles().stream()
                .collect(Collectors.toMap(AdminRoleDTO::getId, Function.identity(), (a, b) -> a));
        List<AdminRoleVO> result = new ArrayList<>(owned.size());
        for (Long roleId : owned) {
            AdminRoleDTO role = byId.get(roleId);
            if (role != null) {
                result.add(toVO(role));
            }
        }
        return result;
    }

    /**
     * 给用户整体重设角色，返回变更后的角色对象列表。
     *
     * @param operatorId 当前登录管理员，SPI 侧据此做自我豁免判定
     */
    public List<AdminRoleVO> assign(Long userId, List<Long> roleIds, Long operatorId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
        List<Long> safeRoleIds = roleIds == null ? Collections.emptyList() : roleIds;
        AdminUserSpi spi = requireSpi();
        List<Long> changed = spi.setUserRoles(userId, safeRoleIds, operatorId);
        log.info("[管理后台] 角色分配: userId={}, roleIds={}, operator={}", userId, safeRoleIds, operatorId);
        List<Long> owned = changed == null ? List.of() : changed;
        Map<Long, AdminRoleDTO> byId = spi.listRoles().stream()
                .collect(Collectors.toMap(AdminRoleDTO::getId, Function.identity(), (a, b) -> a));
        List<AdminRoleVO> result = new ArrayList<>(owned.size());
        for (Long roleId : owned) {
            AdminRoleDTO role = byId.get(roleId);
            if (role != null) {
                result.add(toVO(role));
            }
        }
        return result;
    }

    private AdminUserSpi requireSpi() {
        AdminUserSpi spi = adminUserSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户模块未装配，后台无法分配角色");
        }
        return spi;
    }

    private AdminRoleVO toVO(AdminRoleDTO role) {
        AdminRoleVO vo = new AdminRoleVO();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setDescription(role.getDescription());
        return vo;
    }
}
