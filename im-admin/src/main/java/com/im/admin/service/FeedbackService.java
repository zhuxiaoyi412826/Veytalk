package com.im.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.admin.dto.req.FeedbackQuery;
import com.im.admin.dto.req.FeedbackReplyRequest;
import com.im.admin.dto.req.FeedbackSubmitRequest;
import com.im.admin.dto.vo.FeedbackVO;
import com.im.admin.entity.Feedback;
import com.im.admin.enums.FeedbackType;
import com.im.admin.mapper.FeedbackMapper;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminUserDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminUserSpi;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 意见反馈：前台设置页提交 + 后台检索/查看/回复/删除。
 *
 * <p>表归 im-admin 所有（与公告、版本管理同一先例：不新增 SPI 契约、不动任何 pom 依赖）。
 * 提交人昵称不冗余进表，后台列表用 {@link AdminUserSpi#listByIds} <b>批量</b>解析——
 * 一屏最多 {@value #MAX_PAGE_SIZE} 行，逐行 {@code detail} 就是几十次查询，
 * 而这个接口存在的理由恰好是「后台不能自己 join 用户表」，N+1 只能在这里堵掉。
 * 账号已注销时 SPI 查不到，{@code userName} 为 {@code null}，前端显示「已注销」。
 *
 * <p><b>用户提交端点不设权限点</b>：任何登录用户都能提反馈，防刷靠 {@code @RateLimit(Dimension.USER)}
 * （在 {@code FeedbackController} 上），不靠权限。后台 CRUD 才走 {@code system:feedback} 权限点。
 *
 * <p>状态机只有 {@code 0 待处理 → 1 已回复} 一条边：{@link #reply} 写入即置 1 并记回复人/时刻，
 * 再次回复覆盖旧文（反馈是客服台账不是合同原件，不做回复版本化）。删除是物理删行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 单页上限：一屏几十行足够客服翻阅，与公告/版本管理同档，避免批量取昵称时一次拉回过多用户 */
    private static final long MAX_PAGE_SIZE = 50;

    private final FeedbackMapper feedbackMapper;
    private final ObjectProvider<AdminUserSpi> adminUserSpiProvider;

    /* ==================== 前台提交 ==================== */

    /**
     * 用户提交一条反馈。{@code userId} 一律取登录态，不接受入参（防止替别人提反馈）。
     *
     * <p>入库前做两件收敛：类型码经 {@link FeedbackType#of} 校验，未知码直接拒（不静默归到 OTHER）；
     * 正文 trim 后为空拒收（前端 {@code @NotBlank} 挡不住「全是空格」这种），联系方式空串归一成 null。
     */
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO submit(FeedbackSubmitRequest request, Long userId) {
        BusinessException.throwIf(userId == null, ResultCode.UNAUTHORIZED, "登录态缺失，无法提交反馈");
        FeedbackType type = FeedbackType.of(request.getType());
        BusinessException.throwIf(type == null, ResultCode.BAD_REQUEST, "未知的反馈类型：" + request.getType());
        String content = request.getContent() == null ? "" : request.getContent().trim();
        BusinessException.throwIf(content.isEmpty(), ResultCode.BAD_REQUEST, "反馈内容不能为空");

        Feedback row = new Feedback();
        row.setUserId(userId);
        row.setType(type.getCode());
        row.setContent(content);
        row.setContact(trimToNull(request.getContact()));
        row.setStatus(Feedback.STATUS_PENDING);
        feedbackMapper.insert(row);
        log.info("[意见反馈] 用户提交: id={}, userId={}, type={}", row.getId(), userId, type.getCode());
        return toVO(row, null);
    }

    /* ==================== 后台 ==================== */

    /**
     * 后台列表：按创建时间倒序（客服默认看最新的），可按类型/状态过滤、关键词在正文与联系方式上模糊匹配。
     *
     * <p>传了不认识的类型码不当作「查全部」——那会让一个筛错的调用看到全表并以为筛对了。
     */
    public PageResult<FeedbackVO> adminPage(FeedbackQuery query) {
        long current = Math.max(1, query.safeCurrent());
        long size = Math.min(Math.max(1, query.safeSize()), MAX_PAGE_SIZE);
        FeedbackType type = TextUtil.isNotBlank(query.getType()) ? FeedbackType.of(query.getType()) : null;
        BusinessException.throwIf(TextUtil.isNotBlank(query.getType()) && type == null,
                ResultCode.BAD_REQUEST, "未知的反馈类型：" + query.getType());
        String keyword = trimToNull(query.getKeyword());

        Page<Feedback> page = feedbackMapper.selectPage(Page.of(current, size),
                Wrappers.<Feedback>lambdaQuery()
                        .eq(type != null, Feedback::getType, type == null ? null : type.getCode())
                        .eq(query.getStatus() != null, Feedback::getStatus, query.getStatus())
                        .and(keyword != null, w -> w.like(Feedback::getContent, keyword)
                                .or().like(Feedback::getContact, keyword))
                        .orderByDesc(Feedback::getCreateTime)
                        .orderByDesc(Feedback::getId));

        List<Feedback> rows = page.getRecords();
        Map<Long, String> nameById = resolveUserNames(rows.stream().map(Feedback::getUserId).toList());
        List<FeedbackVO> records = rows.stream().map(row -> toVO(row, nameById.get(row.getUserId()))).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public FeedbackVO detail(Long id) {
        Feedback row = require(id);
        Map<Long, String> nameById = resolveUserNames(List.of(row.getUserId()));
        return toVO(row, nameById.get(row.getUserId()));
    }

    /**
     * 后台回复：写入回复原文、置 {@code status=1}、记回复人与时刻。再次回复覆盖旧文并刷新两列。
     */
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO reply(Long id, FeedbackReplyRequest request, Long operatorId) {
        Feedback row = require(id);
        String reply = request.getReply().trim();
        BusinessException.throwIf(reply.isEmpty(), ResultCode.BAD_REQUEST, "回复内容不能为空");
        row.setReply(reply);
        row.setStatus(Feedback.STATUS_REPLIED);
        row.setReplyBy(operatorId);
        row.setReplyTime(LocalDateTime.now());
        feedbackMapper.updateById(row);
        log.info("[意见反馈] 后台回复: id={}, operator={}", id, operatorId);
        Map<Long, String> nameById = resolveUserNames(List.of(row.getUserId()));
        return toVO(row, nameById.get(row.getUserId()));
    }

    /**
     * 删除：物理删行（后台清理已处置完的台账）。不启逻辑删除，与 im_announcement / im_client_release 同口径。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        require(id);
        feedbackMapper.deleteById(id);
        log.info("[意见反馈] 删除: id={}, operator={}", id, operatorId);
    }

    /* ==================== 内部 ==================== */

    private Feedback require(Long id) {
        BusinessException.throwIf(id == null, ResultCode.BAD_REQUEST, "缺少反馈 ID");
        Feedback row = feedbackMapper.selectById(id);
        BusinessException.throwIf(row == null, ResultCode.NOT_FOUND, "反馈不存在");
        return row;
    }

    /**
     * 批量把用户 ID 解析成昵称。SPI 缺席（im-user 未装载）或账号已注销时，对应 ID 不在结果里，
     * 调用方按 {@code null} 处理（前端显示「已注销」）。绝不逐行查询——那是 N+1。
     */
    private Map<Long, String> resolveUserNames(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        AdminUserSpi spi = adminUserSpiProvider.getIfAvailable();
        if (spi == null) {
            return Map.of();
        }
        List<AdminUserDTO> users = spi.listByIds(ids);
        if (users == null || users.isEmpty()) {
            return Map.of();
        }
        return users.stream()
                .filter(u -> u != null && u.getUserId() != null)
                .collect(Collectors.toMap(AdminUserDTO::getUserId,
                        u -> u.getNickname() == null ? "" : u.getNickname(),
                        (a, b) -> a));
    }

    private FeedbackVO toVO(Feedback row, String userName) {
        FeedbackType type = FeedbackType.of(row.getType());
        return FeedbackVO.builder()
                .id(row.getId())
                .userId(row.getUserId())
                .userName(userName)
                .type(row.getType())
                .typeName(type == null ? row.getType() : type.getDisplayName())
                .content(row.getContent())
                .contact(row.getContact())
                .status(row.getStatus())
                .statusName(row.getStatus() != null && row.getStatus() == Feedback.STATUS_REPLIED
                        ? "已回复" : "待处理")
                .reply(row.getReply())
                .replyBy(row.getReplyBy())
                .replyTime(row.getReplyTime())
                .createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime())
                .build();
    }

    /** 空串归一成 null：让「联系方式留空」在库里是 NULL 而不是 ''。 */
    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

