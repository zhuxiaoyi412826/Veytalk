package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.user.dto.req.AgreementAcceptItem;
import com.im.user.dto.vo.AgreementPendingVO;
import com.im.user.dto.vo.AgreementVO;
import com.im.user.entity.Agreement;
import com.im.user.entity.UserAgreement;
import com.im.user.mapper.AgreementMapper;
import com.im.user.mapper.UserAgreementMapper;
import com.im.user.service.AgreementService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 法定文本与同意留痕实现。
 *
 * <p>整套判断都绕着一条原则转：<b>同意只对具体版本有效</b>。不存在「同意了隐私政策」这种
 * 泛指的状态，只有「在某时刻对 code=privacy、version=3 那一行点了头」。所以：
 * 提交来的版本号必须与当前生效行完全一致才收，不一致就是让他重读；
 * 留痕写的也是那个版本行的 id 与版本号的快照，而不是「他同意过 privacy」的一句话。
 *
 * <p>没有加缓存。当前生效版是每个 code 一行的索引点查，而协议发布是低频但<b>要求立刻全端生效</b>
 * 的动作（法务改了文本，下一秒钟的注册页就该读到新版）；挂一层 Redis 换来的是「刚发布还读到旧版」
 * 这个说不清的窗口，不值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgreementServiceImpl implements AgreementService {

    /** User-Agent 列宽，超出截断：留个能认出设备的开头就够，不必存整串 */
    private static final int UA_MAX = 255;

    private final AgreementMapper agreementMapper;
    private final UserAgreementMapper userAgreementMapper;

    @Override
    public List<AgreementVO> listPublished() {
        List<Agreement> published = publishedAll();
        List<AgreementVO> list = new ArrayList<>(published.size());
        for (Agreement row : published) {
            list.add(toVO(row, false));
        }
        return list;
    }

    @Override
    public AgreementVO view(String code, Integer version, boolean withContent) {
        if (TextUtil.isBlank(code)) {
            throw new BusinessException(ResultCode.AGREEMENT_NOT_FOUND);
        }
        Agreement row = version == null
                ? publishedOf(code.trim())
                : historicOf(code.trim(), version);
        if (row == null) {
            // 草稿态走不进这个分支：两个查询都把 status=0 挡在外面，未定稿的文案对外不存在
            throw new BusinessException(ResultCode.AGREEMENT_NOT_FOUND);
        }
        return toVO(row, withContent);
    }

    @Override
    public AgreementPendingVO pendingOf(Long userId) {
        List<Agreement> published = publishedAll();
        if (userId == null || published.isEmpty()) {
            // 库里一份都没发布过时不弹框：没有文本可以同意，弹一个空框只会让用户以为坏了
            return emptyPending();
        }
        List<Long> ids = new ArrayList<>(published.size());
        for (Agreement row : published) {
            ids.add(row.getId());
        }
        Set<Long> acceptedIds = new HashSet<>();
        Set<Long> anyConsentIds = new HashSet<>();
        List<UserAgreement> records = userAgreementMapper.selectList(new LambdaQueryWrapper<UserAgreement>()
                .eq(UserAgreement::getUserId, userId)
                .in(UserAgreement::getAgreementId, ids));
        for (UserAgreement record : records) {
            anyConsentIds.add(record.getAgreementId());
            if (record.accepted()) {
                acceptedIds.add(record.getAgreementId());
            }
        }
        List<AgreementVO> pending = new ArrayList<>();
        boolean dismissible = true;
        for (Agreement row : published) {
            if (acceptedIds.contains(row.getId())) {
                continue;
            }
            if (!row.mandatory() && anyConsentIds.contains(row.getId())) {
                // 可选项表过态就算数，哪怕表的是「不」：他明确拒过服务条款，
                // 每次都再问一遍不是合规，是骚扰
                continue;
            }
            pending.add(toVO(row, false));
            if (row.mandatory()) {
                dismissible = false;
            }
        }
        return AgreementPendingVO.builder()
                .need(!pending.isEmpty())
                .dismissible(dismissible)
                .agreements(pending)
                .build();
    }

    @Override
    public boolean needConsent(Long userId) {
        return Boolean.TRUE.equals(pendingOf(userId).getNeed());
    }

    @Override
    public void validateForRegister(List<AgreementAcceptItem> items) {
        Map<String, Agreement> published = publishedMapByCode();
        if (published.isEmpty()) {
            // 迁移脚本没跑（一份文本都没发布）时放行注册：那是部署问题，
            // 不该让所有新账号都收到一句「请先同意协议」却连协议长什么样都看不到
            log.warn("[协议] 无任何已发布协议文本，注册校验跳过——请先执行 sql/migration_agreement.sql");
            return;
        }
        Map<String, AgreementAcceptItem> submitted = indexByCode(items, true);

        for (Map.Entry<String, Agreement> entry : published.entrySet()) {
            Agreement row = entry.getValue();
            if (!row.mandatory()) {
                continue;
            }
            AgreementAcceptItem item = submitted.get(entry.getKey());
            if (item == null || !item.accepted()) {
                // 必签项没勾和勾了「不同意」是同一种失败：他没有接受这份文本
                throw new BusinessException(ResultCode.AGREEMENT_REQUIRED);
            }
            if (!row.getVersion().equals(item.getVersion())) {
                throw new BusinessException(ResultCode.AGREEMENT_VERSION_MISMATCH);
            }
        }
    }

    @Override
    public void recordConsents(Long userId, List<AgreementAcceptItem> items, String source) {
        if (userId == null || items == null || items.isEmpty()) {
            return;
        }
        Map<String, AgreementAcceptItem> submitted = indexByCode(items, false);
        String ip = SecurityUtil.getClientIp();
        String userAgent = userAgent();
        for (Map.Entry<String, AgreementAcceptItem> entry : submitted.entrySet()) {
            AgreementAcceptItem item = entry.getValue();
            Agreement row = publishedOf(entry.getKey());
            if (row == null || !row.getVersion().equals(item.getVersion())) {
                // 上游已经校验过一轮，走到这里只可能是「就在他点提交的这几毫秒里法务发布了新版」。
                // 这一项不写：拿一份他没见过的新文本的 version 去落留痕是伪造证据，
                // 让他重弹一次框比什么都重要
                log.warn("[协议] 用户 {} 提交的 {} v{} 已非当前生效版（现为 v{}），该条留痕未写入",
                        userId, entry.getKey(), item.getVersion(), row == null ? "无" : row.getVersion());
                continue;
            }
            int action = item.accepted() ? UserAgreement.ACTION_ACCEPT : UserAgreement.ACTION_REJECT;
            Long existed = userAgreementMapper.selectCount(new LambdaQueryWrapper<UserAgreement>()
                    .eq(UserAgreement::getUserId, userId)
                    .eq(UserAgreement::getAgreementId, row.getId())
                    .eq(UserAgreement::getAction, action));
            if (existed != null && existed > 0) {
                // 同一版同一表态记一次就够：多端登录不该把证据刷成一屏重复行
                continue;
            }
            UserAgreement record = new UserAgreement();
            record.setUserId(userId);
            record.setAgreementId(row.getId());
            record.setCode(row.getCode());
            record.setVersion(row.getVersion());
            record.setAction(action);
            record.setSource(source);
            record.setIp(truncate(ip, 64));
            record.setUserAgent(truncate(userAgent, UA_MAX));
            userAgreementMapper.insert(record);
            log.info("[协议] 用户 {} 对 {} v{} {}已留痕: source={}, ip={}",
                    userId, row.getCode(), row.getVersion(), action == UserAgreement.ACTION_ACCEPT ? "同意" : "拒绝",
                    source, ip);
        }
    }

    @Override
    public void consent(Long userId, List<AgreementAcceptItem> items) {
        validateVersions(items);
        recordConsents(userId, items, UserAgreement.SOURCE_DIALOG);
    }

    /**
     * 版本必须仍是当前生效版；不要求必签项齐全（弹窗可以只交其中几项，剩下的下次再问）。
     */
    private void validateVersions(List<AgreementAcceptItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "至少要提交一项协议表态");
        }
        Map<String, Agreement> published = publishedMapByCode();
        for (AgreementAcceptItem item : items) {
            if (!Agreement.isKnownCode(item.getCode())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "未知的协议标识：" + item.getCode());
            }
            Agreement row = published.get(item.getCode().trim());
            if (row == null) {
                throw new BusinessException(ResultCode.AGREEMENT_NOT_FOUND);
            }
            if (!row.getVersion().equals(item.getVersion())) {
                throw new BusinessException(ResultCode.AGREEMENT_VERSION_MISMATCH);
            }
        }
    }

    /**
     * 按 code 归集提交项。
     *
     * @param strict true 时未知 code 直接拒（注册路径，前端拼错了要当场响），
     *               false 时放过（留痕路径，宁可不写一条也不能因为一个多余项就把整批丢掉）
     */
    private Map<String, AgreementAcceptItem> indexByCode(List<AgreementAcceptItem> items, boolean strict) {
        Map<String, AgreementAcceptItem> map = new LinkedHashMap<>();
        if (items == null) {
            return map;
        }
        for (AgreementAcceptItem item : items) {
            if (item == null || TextUtil.isBlank(item.getCode())) {
                continue;
            }
            String code = item.getCode().trim();
            if (!Agreement.isKnownCode(code)) {
                if (strict) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "未知的协议标识：" + code);
                }
                log.warn("[协议] 忽略未知协议标识: {}", code);
                continue;
            }
            if (item.getVersion() == null || item.getVersion() < 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "协议版本号不能为空");
            }
            // 同一 code 重复提交时取后一条：客户端去重是它的事，服务端至少不能写出两条互相矛盾的表态
            map.put(code, item);
        }
        return map;
    }

    /** 当前生效的全部版本，按「用户协议、隐私政策、服务条款」的固定顺序，未登记的 code 垫在后面 */
    private List<Agreement> publishedAll() {
        List<Agreement> rows = selectForUser(new LambdaQueryWrapper<Agreement>()
                .eq(Agreement::getStatus, Agreement.STATUS_PUBLISHED));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Agreement> byCode = new HashMap<>();
        for (Agreement row : rows) {
            Agreement kept = byCode.get(row.getCode());
            // 万一并发发布出两行已发布，取版本号大的那个当生效版，别让列表出现两个同 code 的链接
            if (kept == null || gt(row.getVersion(), kept.getVersion())) {
                byCode.put(row.getCode(), row);
            }
        }
        List<Agreement> ordered = new ArrayList<>(byCode.size());
        for (String code : Agreement.allCodes()) {
            Agreement row = byCode.remove(code);
            if (row != null) {
                ordered.add(row);
            }
        }
        // 剩下的是一版新增、还没进 allCodes() 的协议：宁可排在末尾公示出来，也不要静默丢掉一份法定文本
        List<String> rest = new ArrayList<>(byCode.keySet());
        rest.sort(String::compareTo);
        for (String code : rest) {
            ordered.add(byCode.get(code));
        }
        return ordered;
    }

    private Map<String, Agreement> publishedMapByCode() {
        Map<String, Agreement> map = new LinkedHashMap<>();
        for (Agreement row : publishedAll()) {
            map.put(row.getCode(), row);
        }
        return map;
    }

    /** 某个 code 的当前生效版，没有则 null */
    private Agreement publishedOf(String code) {
        List<Agreement> rows = selectForUser(new LambdaQueryWrapper<Agreement>()
                .eq(Agreement::getCode, code)
                .eq(Agreement::getStatus, Agreement.STATUS_PUBLISHED)
                .orderByDesc(Agreement::getVersion)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 某个 code 的指定历史版本。<b>草稿态不可见</b>（ne status=0）：
     * 未定稿的文本对外不存在，哪怕拿着 id 来猜也猜不到。
     */
    private Agreement historicOf(String code, Integer version) {
        if (version == null || version < 1) {
            return null;
        }
        List<Agreement> rows = selectForUser(new LambdaQueryWrapper<Agreement>()
                .eq(Agreement::getCode, code)
                .eq(Agreement::getVersion, version)
                .ne(Agreement::getStatus, Agreement.STATUS_DRAFT)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 用户侧三个读路径共用的降级：查不动 {@code im_agreement} 时当作「没有已发布文本」。
     *
     * <p>表没建（升级了 jar 而忘了跑 {@code sql/migration_agreement.sql}）时，MyBatis 抛的是
     * {@code BadSqlGrammarException}，不加这一层它会一路冒到全局兜底变成 5000「系统异常」——
     * 而最刺眼的后果落在<b>注册</b>上：新账号入口直接被打没，{@code validateForRegister} 里那句
     * 「一份都没发布就放行注册」根本走不到（它在查库那一步就炸了）。降级后的形状是：公示页回
     * 2022、注册照常放行、弹窗不弹，日志里留一条 warn 指回脚本。留着不降级比读不到文本更危险：
     * 合规缺失不该以「全站不能注册」的形式被察觉。
     *
     * <p>★ 只有对外这几条路降级，后台 {@code AdminAgreementService} 那条不降——
     * 发布合同的人必须看见真实的报错，而不是一个看起来「一稿都没有」的空列表。
     */
    private List<Agreement> selectForUser(LambdaQueryWrapper<Agreement> wrapper) {
        try {
            return agreementMapper.selectList(wrapper);
        } catch (DataAccessException e) {
            log.warn("[协议] 读取 im_agreement 失败，按「无已发布文本」降级——请先执行 sql/migration_agreement.sql：{}",
                    e.getMessage());
            return List.of();
        }
    }

    private AgreementPendingVO emptyPending() {
        return AgreementPendingVO.builder()
                .need(false)
                .dismissible(true)
                .agreements(List.of())
                .build();
    }

    private AgreementVO toVO(Agreement row, boolean withContent) {
        AgreementVO.AgreementVOBuilder builder = AgreementVO.builder()
                .code(row.getCode())
                .codeName(Agreement.nameOf(row.getCode()))
                .version(row.getVersion())
                .title(row.getTitle())
                .summary(row.getSummary())
                .required(row.mandatory())
                .effectiveTime(row.getEffectiveTime());
        if (withContent) {
            builder.content(row.getContent());
        }
        return builder.build();
    }

    private String userAgent() {
        HttpServletRequest request = SecurityUtil.getRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    private boolean gt(Integer a, Integer b) {
        int left = a == null ? 0 : a;
        int right = b == null ? 0 : b;
        return left > right;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
