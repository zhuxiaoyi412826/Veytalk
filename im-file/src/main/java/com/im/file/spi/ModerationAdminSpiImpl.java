package com.im.file.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminModerationLogDTO;
import com.im.common.domain.AdminModerationQueryDTO;
import com.im.common.domain.FileDTO;
import com.im.common.domain.ModerationScoreDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminModerationSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.TextUtil;
import com.im.file.convert.FileConvert;
import com.im.file.entity.FileEntity;
import com.im.file.entity.ModerationLog;
import com.im.file.mapper.FileMapper;
import com.im.file.mapper.ModerationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台审核记录查询契约的实现（{@link AdminModerationSpi}，五期·路线 2）。
 *
 * <p>取数形状与 {@code MessageAdminSpiImpl} 同构但便宜得多：流水表低频（命中才有行）、
 * 无正文大字段，条件全是等值 + 时间区间，排序固定主键倒序，所以没有任何「必须带范围」
 * 的硬约束。时间过滤走 {@code create_time} 而不是换算成 ID 区间：这张表的量级
 * （一年到不了几万行）不值得为它设计雪花边界换算。
 *
 * <p>昵称回填一页一次 {@code listByIds}；查不到（含已注销被匿名化）落「已注销用户」占位——
 * 注销恰恰是最要追溯的场景，占位比空白强。
 *
 * <p>{@link #evidence} 只回元数据不回字节：字节由 im-admin 拿 {@code FileStorageSpi#openStream}
 * 自己开，与后台看举报证据同一套做法。也不走 {@code FileService#requireAccessible}：那套口径是
 * 「上传者或同会话成员」，而管理员两者都不是，门是 {@code system:log} 那一个权限点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationAdminSpiImpl implements AdminModerationSpi {

    /** 单页上限：一行带评分列表，比纯数字列贵，与消息检索同档收到 50 */
    private static final long MAX_PAGE_SIZE = 50;
    private static final String DELETED_NICKNAME = "已注销用户";

    private final ModerationLogMapper moderationLogMapper;
    private final FileMapper fileMapper;
    private final UserQuerySpi userQuerySpi;
    private final JsonUtil jsonUtil;

    @Override
    public PageResult<AdminModerationLogDTO> pageLogs(AdminModerationQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);

        LambdaQueryWrapper<ModerationLog> wrapper = Wrappers.<ModerationLog>lambdaQuery()
                .eq(query.getUserId() != null, ModerationLog::getUserId, query.getUserId())
                .eq(TextUtil.isNotBlank(query.getScene()), ModerationLog::getScene, trim(query.getScene()))
                .eq(TextUtil.isNotBlank(query.getHitClass()), ModerationLog::getHitClass, trim(query.getHitClass()))
                .ge(query.getStartTime() != null, ModerationLog::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, ModerationLog::getCreateTime, query.getEndTime())
                .orderByDesc(ModerationLog::getId);

        Page<ModerationLog> page = moderationLogMapper.selectPage(Page.of(current, size), wrapper);
        List<AdminModerationLogDTO> records = toRows(page.getRecords());
        return new PageResult<>(records, page.getTotal(), current, size, page.getPages());
    }

    @Override
    public FileDTO evidence(Long logId) {
        BusinessException.throwIf(logId == null, ResultCode.BAD_REQUEST, "缺少审核记录 ID");
        ModerationLog row = moderationLogMapper.selectById(logId);
        // 记录不存在要说是「不存在」而不是回 null：后者会被读成「有记录但无图」，
        // 于是拿一个猜的主键就能枚举出「哪些 ID 有记录」，这两件事必须能分开
        BusinessException.throwIf(row == null, ResultCode.NOT_FOUND, "审核记录不存在");
        if (row.getEvidenceFileId() == null) {
            return null;
        }
        FileEntity file = fileMapper.selectById(row.getEvidenceFileId());
        // 记录说有一张图、im_file 里却拿不到（字节被人工清过或逻辑删了）：据实回 null，
        // 不在这里编一个指向不存在对象的 DTO 把问题带到响应头里去
        return file == null ? null : FileConvert.toDTO(file);
    }

    /* ==================== 内部 ==================== */

    private List<AdminModerationLogDTO> toRows(List<ModerationLog> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        for (ModerationLog row : rows) {
            userIds.add(row.getUserId());
        }
        Map<Long, UserBriefDTO> users = userQuerySpi.listByIds(userIds);

        List<AdminModerationLogDTO> result = new ArrayList<>(rows.size());
        for (ModerationLog row : rows) {
            UserBriefDTO user = users.get(row.getUserId());
            result.add(AdminModerationLogDTO.builder()
                    .id(row.getId())
                    .userId(row.getUserId())
                    .nickname(user == null || TextUtil.isBlank(user.getNickname())
                            ? DELETED_NICKNAME : user.getNickname())
                    .scene(row.getScene())
                    .hitClass(row.getHitClass())
                    .hitProbability(row.getHitProbability())
                    .threshold(row.getThreshold())
                    .fileName(row.getFileName())
                    .fileSize(row.getFileSize())
                    .conversationId(row.getConversationId())
                    .scores(parseScores(row.getScores()))
                    .evidenceFileId(row.getEvidenceFileId())
                    .createTime(row.getCreateTime())
                    .build());
        }
        return result;
    }

    /** 评分 JSON 解析失败给 null 而不是抛：留痕字段坏了不该把整页查询掀翻 */
    private List<ModerationScoreDTO> parseScores(String json) {
        return jsonUtil.toList(json, ModerationScoreDTO.class);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
