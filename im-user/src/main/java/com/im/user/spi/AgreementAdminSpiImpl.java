package com.im.user.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminAgreementConsentDTO;
import com.im.common.domain.AdminAgreementConsentQueryDTO;
import com.im.common.domain.AdminAgreementDTO;
import com.im.common.domain.AdminAgreementQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminAgreementSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.user.entity.Agreement;
import com.im.user.entity.UserAgreement;
import com.im.user.mapper.AgreementMapper;
import com.im.user.mapper.UserAgreementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台协议与同意留痕契约的实现（{@link AdminAgreementSpi}）。
 *
 * <p>类名带模块前缀的理由见 {@code AdminMetricsSpi} 接口注释——同接口的实现类在全上下文里必须类名唯一，
 * 否则扫包时 {@code ConflictingBeanDefinitionException}。
 *
 * <p>与用户端 {@code AgreementServiceImpl} 分成两套读法：那边只认已发布版本且不给内部审计字段，
 * 这边要看到草稿与归档、要能改草稿的正文。两套口径硬拧成一个 Service，
 * 迟早出现「后台预览顺手把草稿公示出去了」这类事故，所以各走各的 mapper。
 *
 * <p><b>发布是唯一一个会影响全服的写动作</b>：一次 {@code updateById} 把旧生效版顶成归档、
 * 把新版推上前台，所有端的注册页与弹窗在同一毫秒读到新文本。这里没有加分布式锁——
 * 「同 code 至多一行已发布」靠发布前的那次查重与事务内的两次 UPDATE 维持，
 * 两个人同时点发布的概率与后果都远低于它值得的复杂度；真出现两行已发布，
 * 用户端的 {@code publishedAll()} 也会取版本号大的那一行兜住，不会读出两个生效版。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgreementAdminSpiImpl implements AdminAgreementSpi {

    /** 单页上限，与 {@code AdminUserSpi} 的约定同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    private static final int TITLE_MAX = 120;
    private static final int SUMMARY_MAX = 500;
    /** 正文上限：一份完整的中文隐私政策约 8000 字，留够十倍余量，同时挡住把整本书粘进来的误操作 */
    private static final int CONTENT_MAX = 200_000;
    private static final int REASON_MAX = 255;
    private static final String DEFAULT_TITLE_SUFFIX = "（草稿）";

    private final AgreementMapper agreementMapper;
    private final UserAgreementMapper userAgreementMapper;
    private final UserQuerySpi userQuerySpi;

    @Override
    public PageResult<AdminAgreementDTO> pageAgreements(AdminAgreementQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = TextUtil.isBlank(query.getKeyword()) ? null : query.getKeyword().trim();
        if (keyword != null && keyword.length() > TITLE_MAX) {
            keyword = keyword.substring(0, TITLE_MAX);
        }
        LambdaQueryWrapper<Agreement> wrapper = new LambdaQueryWrapper<Agreement>()
                .eq(TextUtil.isNotBlank(query.getCode()), Agreement::getCode, query.getCode())
                .eq(query.getStatus() != null, Agreement::getStatus, query.getStatus())
                .like(keyword != null, Agreement::getTitle, keyword)
                .orderByAsc(Agreement::getCode)
                .orderByDesc(Agreement::getVersion);
        Page<Agreement> result = agreementMapper.selectPage(new Page<>(current, size), wrapper);
        List<Agreement> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, String> publisherNames = namesOf(publisherIds(records));
        List<AdminAgreementDTO> dtos = new ArrayList<>(records.size());
        for (Agreement row : records) {
            // 列表不带正文：toDTO(row, name, false)
            dtos.add(toDTO(row, publisherNames.get(row.getPublishBy()), false));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminAgreementDTO getDetail(Long id) {
        Agreement row = agreementMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.AGREEMENT_NOT_FOUND);
        }
        return toDTO(row, publisherName(row.getPublishBy()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminAgreementDTO createDraft(AdminAgreementDTO dto, Long operatorId) {
        String code = requireCode(dto.getCode());
        String content = requireContent(dto.getContent());
        Agreement row = new Agreement();
        row.setCode(code);
        // 版本号服务端算：取该 code 下历史最大 version + 1（含草稿与归档，号段不能重复）
        row.setVersion(nextVersion(code));
        row.setTitle(resolveTitle(dto.getTitle(), code, row.getVersion()));
        row.setSummary(truncate(TextUtil.isBlank(dto.getSummary()) ? "" : dto.getSummary().trim(), SUMMARY_MAX));
        row.setContent(content);
        row.setRequired(dto.getRequired() == null ? defaultRequired(code) : normalizeRequired(dto.getRequired()));
        row.setStatus(Agreement.STATUS_DRAFT);
        row.setRemark(truncate(dto.getRemark(), REASON_MAX));
        agreementMapper.insert(row);
        log.info("[后台协议管理] operator={} 起草 {} v{}（required={}）", operatorId, code, row.getVersion(), row.getRequired());
        return toDTO(row, null, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminAgreementDTO updateDraft(AdminAgreementDTO dto, Long operatorId) {
        if (dto.getId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少协议版本 ID");
        }
        Agreement row = agreementMapper.selectById(dto.getId());
        if (row == null) {
            throw new BusinessException(ResultCode.AGREEMENT_NOT_FOUND);
        }
        if (!row.draft()) {
            // 已发布与已归档的版本是有人对着点过同意的历史文本：改它等于把那条留痕变成伪证
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "已发布的版本不可修改，请在 " + Agreement.nameOf(row.getCode()) + " 下新建版本");
        }
        if (dto.getContent() != null) {
            row.setContent(requireContent(dto.getContent()));
        }
        if (!TextUtil.isBlank(dto.getTitle())) {
            row.setTitle(truncate(dto.getTitle().trim(), TITLE_MAX));
        }
        if (dto.getSummary() != null) {
            row.setSummary(truncate(dto.getSummary().trim(), SUMMARY_MAX));
        }
        if (dto.getRequired() != null) {
            row.setRequired(normalizeRequired(dto.getRequired()));
        }
        if (dto.getRemark() != null) {
            row.setRemark(truncate(dto.getRemark(), REASON_MAX));
        }
        // code 与 version 不可改：改 code 等于把这行换成另一份法定文本，改 version 会让留痕对不上账
        agreementMapper.updateById(row);
        log.info("[后台协议管理] operator={} 编辑草稿 {} v{}", operatorId, row.getCode(), row.getVersion());
        return toDTO(row, publisherName(row.getPublishBy()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminAgreementDTO publish(Long id, Long operatorId, String remark) {
        Agreement row = agreementMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.AGREEMENT_NOT_FOUND);
        }
        if (row.published()) {
            // 幂等：重复点「发布」不重新盖时间，也不把同一版再归档一次
            return toDTO(row, publisherName(row.getPublishBy()), true);
        }
        if (row.getStatus() != null && row.getStatus() == Agreement.STATUS_ARCHIVED) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "已归档的版本不可重新发布，请新建版本");
        }
        LocalDateTime now = LocalDateTime.now();
        // 先把同 code 那份生效中的顶成归档，再把自己推上去：反过来会出现一个「零份生效」的瞬间，
        // 那个瞬间里注册页读到空清单、直接放行一个没签任何协议的账号
        Agreement archive = new Agreement();
        archive.setStatus(Agreement.STATUS_ARCHIVED);
        int archived = agreementMapper.update(archive, new LambdaQueryWrapper<Agreement>()
                .eq(Agreement::getCode, row.getCode())
                .eq(Agreement::getStatus, Agreement.STATUS_PUBLISHED));
        row.setStatus(Agreement.STATUS_PUBLISHED);
        row.setPublishTime(now);
        row.setEffectiveTime(now);
        row.setPublishBy(operatorId);
        if (!TextUtil.isBlank(remark)) {
            row.setRemark(truncate(remark.trim(), REASON_MAX));
        }
        agreementMapper.updateById(row);
        log.warn("[后台协议管理] operator={} 发布 {} v{} 生效，同时归档旧版 {} 行；全端注册页与弹窗即刻读新版",
                operatorId, row.getCode(), row.getVersion(), archived);
        return toDTO(row, publisherName(row.getPublishBy()), true);
    }

    @Override
    public PageResult<AdminAgreementConsentDTO> pageConsents(AdminAgreementConsentQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        LambdaQueryWrapper<UserAgreement> wrapper = new LambdaQueryWrapper<UserAgreement>()
                .eq(query.getUserId() != null, UserAgreement::getUserId, query.getUserId())
                .eq(query.getAgreementId() != null, UserAgreement::getAgreementId, query.getAgreementId())
                .eq(TextUtil.isNotBlank(query.getCode()), UserAgreement::getCode, query.getCode())
                .eq(query.getAction() != null, UserAgreement::getAction, query.getAction())
                .ge(query.getStartTime() != null, UserAgreement::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, UserAgreement::getCreateTime, query.getEndTime())
                .orderByDesc(UserAgreement::getCreateTime)
                .orderByDesc(UserAgreement::getId);
        Page<UserAgreement> result = userAgreementMapper.selectPage(new Page<>(current, size), wrapper);
        List<UserAgreement> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(current, size);
        }
        return PageResult.of(toConsentDTOs(records), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<AdminAgreementConsentDTO> listUserConsents(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // 不分页：一个人对三份协议的版本数是个位到十几的量级，翻页会把一份证明材料切成两半
        List<UserAgreement> records = userAgreementMapper.selectList(new LambdaQueryWrapper<UserAgreement>()
                .eq(UserAgreement::getUserId, userId)
                .orderByDesc(UserAgreement::getCreateTime)
                .orderByDesc(UserAgreement::getId)
                .last("LIMIT 200"));
        return toConsentDTOs(records);
    }

    /* -------------------------------- 组装 -------------------------------- */

    private List<AdminAgreementConsentDTO> toConsentDTOs(List<UserAgreement> records) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> agreementIds = new HashSet<>();
        for (UserAgreement record : records) {
            if (record.getUserId() != null) {
                userIds.add(record.getUserId());
            }
            if (record.getAgreementId() != null) {
                agreementIds.add(record.getAgreementId());
            }
        }
        Map<Long, UserBriefDTO> users = userIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(userIds);
        Map<Long, Agreement> agreements = agreementIds.isEmpty() ? Map.of() : loadAgreements(agreementIds);
        List<AdminAgreementConsentDTO> dtos = new ArrayList<>(records.size());
        for (UserAgreement record : records) {
            Agreement row = agreements.get(record.getAgreementId());
            UserBriefDTO user = users.get(record.getUserId());
            dtos.add(AdminAgreementConsentDTO.builder()
                    .id(record.getId())
                    .userId(record.getUserId())
                    .account(user == null ? null : user.getUsername())
                    .nickname(user == null ? null : user.getNickname())
                    .agreementId(record.getAgreementId())
                    .code(record.getCode())
                    .codeName(Agreement.nameOf(record.getCode()))
                    .version(record.getVersion())
                    // 标题取当前行，取不到（该版本行被人为删过）时退化成「code 名 + 版本号」：
                    // 证明材料宁可不完整也不能一句 null 糊弄过去
                    .title(row == null ? Agreement.nameOf(record.getCode()) + " v" + record.getVersion() : row.getTitle())
                    .action(record.getAction())
                    .source(record.getSource())
                    .ip(record.getIp())
                    .userAgent(record.getUserAgent())
                    .consentTime(record.getCreateTime())
                    .build());
        }
        return dtos;
    }

    private Map<Long, Agreement> loadAgreements(Set<Long> ids) {
        Map<Long, Agreement> map = new HashMap<>();
        for (Agreement row : agreementMapper.selectBatchIds(ids)) {
            map.put(row.getId(), row);
        }
        return map;
    }

    private Set<Long> publisherIds(List<Agreement> rows) {
        Set<Long> ids = new HashSet<>();
        for (Agreement row : rows) {
            if (row.getPublishBy() != null) {
                ids.add(row.getPublishBy());
            }
        }
        return ids;
    }

    private Map<Long, String> namesOf(Set<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        for (Map.Entry<Long, UserBriefDTO> entry : userQuerySpi.listByIds(userIds).entrySet()) {
            names.put(entry.getKey(), displayName(entry.getValue()));
        }
        return names;
    }

    private String publisherName(Long userId) {
        if (userId == null) {
            return null;
        }
        return displayName(userQuerySpi.getById(userId));
    }

    private String displayName(UserBriefDTO brief) {
        if (brief == null) {
            return "已注销用户";
        }
        return TextUtil.isNotBlank(brief.getNickname()) ? brief.getNickname() : brief.getUsername();
    }

    private AdminAgreementDTO toDTO(Agreement row, String publisherName, boolean withContent) {
        AdminAgreementDTO.AdminAgreementDTOBuilder builder = AdminAgreementDTO.builder()
                .id(row.getId())
                .code(row.getCode())
                .codeName(Agreement.nameOf(row.getCode()))
                .version(row.getVersion())
                .title(row.getTitle())
                .summary(row.getSummary())
                .required(row.getRequired())
                .status(row.getStatus())
                .effectiveTime(row.getEffectiveTime())
                .publishTime(row.getPublishTime())
                .publishBy(row.getPublishBy())
                .publishByName(publisherName)
                .remark(row.getRemark())
                .createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime());
        if (withContent) {
            builder.content(row.getContent());
        }
        return builder.build();
    }

    /* -------------------------------- 校验与兜底 -------------------------------- */

    private String requireCode(String code) {
        String value = code == null ? null : code.trim();
        if (!Agreement.isKnownCode(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知的协议标识：" + code);
        }
        return value;
    }

    private String requireContent(String content) {
        if (TextUtil.isBlank(content)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "协议正文不能为空");
        }
        if (content.length() > CONTENT_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "协议正文过长（" + content.length() + " 字符，上限 " + CONTENT_MAX + "）");
        }
        return content;
    }

    /** 该 code 下的下一个版本号，含草稿与归档，号段永不复用 */
    private int nextVersion(String code) {
        List<Agreement> rows = agreementMapper.selectList(new LambdaQueryWrapper<Agreement>()
                .select(Agreement::getVersion)
                .eq(Agreement::getCode, code)
                .orderByDesc(Agreement::getVersion)
                .last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getVersion() == null ? 1 : rows.get(0).getVersion() + 1;
    }

    /** 标题缺省时按「协议名 v版本号（草稿）」生成，发布后不改（标题是不可变文本的一部分） */
    private String resolveTitle(String title, String code, int version) {
        if (!TextUtil.isBlank(title)) {
            return truncate(title.trim(), TITLE_MAX);
        }
        return truncate(Agreement.nameOf(code) + " v" + version + DEFAULT_TITLE_SUFFIX, TITLE_MAX);
    }

    /** 默认必签口径：隐私政策与用户协议必签，服务条款可拒 */
    private int defaultRequired(String code) {
        return Agreement.CODE_SERVICE.equals(code) ? Agreement.REQUIRED_NO : Agreement.REQUIRED_YES;
    }

    private int normalizeRequired(Integer required) {
        return required != null && required == Agreement.REQUIRED_NO ? Agreement.REQUIRED_NO : Agreement.REQUIRED_YES;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
