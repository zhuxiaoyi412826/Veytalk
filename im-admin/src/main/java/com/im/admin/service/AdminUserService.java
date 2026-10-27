package com.im.admin.service;

import com.im.admin.dto.po.ReportAggPO;
import com.im.admin.dto.req.AdminUserQuery;
import com.im.admin.dto.vo.AdminUserVO;
import com.im.admin.mapper.ReportMapper;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminUserDTO;
import com.im.common.domain.AdminUserQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminUserSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 后台用户管理：翻页找人、看档案、禁用与恢复。
 *
 * <p>本类<b>一行用户数据都不直接读</b>，全部经 {@code AdminUserSpi} 交给 im-user 执行
 * （星型架构：每张表只在归属模块映射一次）。这里负责的是后台这一侧的三件事：
 * 把 REST 入参收敛成 SPI 的查询对象、把「被举报次数」这一列拼上去、
 * 以及挡住「管理员封掉自己」这类自伤调用。
 *
 * <p>被举报次数为什么不放进 SPI：它来自 {@code im_report}，是后台的表。
 * 让用户模块去读它，等于让 im-user 知道「举报队列」这件事存在，
 * 以后举报口径一改就要连动两个模块，边界就糊了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final ObjectProvider<AdminUserSpi> adminUserSpiProvider;
    private final ReportMapper reportMapper;

    /**
     * 用户列表。排序口径由 im-user 写死为注册时间倒序，这里不提供 order by。
     */
    public PageResult<AdminUserVO> page(AdminUserQuery query) {
        AdminUserSpi spi = requireSpi();
        AdminUserQueryDTO dto = AdminUserQueryDTO.builder()
                .keyword(trimToNull(query.getKeyword()))
                .status(query.getStatus())
                .current(query.safeCurrent())
                .size(query.safeSize())
                .build();
        PageResult<AdminUserDTO> source = spi.pageUsers(dto);
        if (source == null || source.getRecords() == null) {
            return PageResult.empty(query.safeCurrent(), query.safeSize());
        }
        Map<Long, Long> pending = pendingCounts(source.getRecords().stream()
                .map(AdminUserDTO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        List<AdminUserVO> records = new ArrayList<>(source.getRecords().size());
        for (AdminUserDTO user : source.getRecords()) {
            records.add(toVO(user, pending.get(user.getUserId())));
        }
        return PageResult.of(records, source.getTotal(), source.getCurrent(), source.getSize());
    }

    /**
     * 单个用户的后台档案，含未处置工单数。
     */
    public AdminUserVO detail(Long userId) {
        AdminUserDTO user = requireSpi().detail(userId);
        BusinessException.throwIf(user == null, ResultCode.USER_NOT_FOUND);
        return toVO(user, pendingCounts(List.of(userId)).get(userId));
    }

    /**
     * 禁用 / 恢复账号。
     *
     * <p>自我豁免在这里挡一道（SPI 实现里还会挡一道）：把管理员封掉自己这件事
     * 做成一个能点的按钮，后果是「后台再也进不去、只能改数据库」，
     * 而这条路径的恢复成本远高于一次条件判断。
     *
     * <p>{@code disableMinutes} 不传就是永久封禁。到期时间不在这里算，只把分钟数往下传：
     * 真正拥有这一列的是 im-user，它得保证「写进去的时间」与「扫出来的时间」用的是同一个时钟。
     *
     * @param reason 事由，会进服务端日志；是否必填沿用处置说明的配置口径由调用方决定
     */
    public AdminUserVO setDisabled(Long userId, boolean disabled, Long operatorId, String reason, Long disableMinutes) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
        BusinessException.throwIf(userId.equals(operatorId), ResultCode.ADMIN_SELF_FORBIDDEN);
        // 禁用必须写事由：它是事后唯一能回答「当时为什么关掉这个人」的东西，
        // 而它只进日志不进表（一期没有操作审计表），下次重启就没了，所以宁可在入口就拦住
        if (disabled) {
            BusinessException.throwIf(reason == null || reason.isBlank(),
                    ResultCode.BAD_REQUEST, "禁用账号必须写明事由");
        }
        AdminUserDTO user = requireSpi().setDisabled(userId, disabled, operatorId, trimToNull(reason),
                disabled ? disableMinutes : null);
        log.info("[管理后台] 账号状态变更: userId={}, disabled={}, minutes={}, operator={}, reason={}",
                userId, disabled, disableMinutes, operatorId, reason);
        BusinessException.throwIf(user == null, ResultCode.USER_NOT_FOUND);
        return toVO(user, pendingCounts(List.of(userId)).get(userId));
    }

    private AdminUserSpi requireSpi() {
        AdminUserSpi spi = adminUserSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户模块未装配，后台无法管理用户");
        }
        return spi;
    }

    /**
     * 一屏用户的未处置工单数：一次 GROUP BY，不逐行查。
     */
    private Map<Long, Long> pendingCounts(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (ReportAggPO.UserCount row : reportMapper.countPendingByUsers(userIds)) {
            result.put(row.getUserId(), row.getCount());
        }
        return result;
    }

    private AdminUserVO toVO(AdminUserDTO user, Long pendingCount) {
        AdminUserVO vo = new AdminUserVO();
        vo.setUserId(user.getUserId());
        vo.setAccount(user.getAccount());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setDisableUntil(user.getDisableUntil());
        vo.setOnline(user.getOnline());
        vo.setBalance(user.getBalance());
        vo.setCreateTime(user.getCreateTime());
        vo.setAdmin(user.getAdmin());
        vo.setPendingReportCount(pendingCount == null ? 0L : pendingCount);
        return vo;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
