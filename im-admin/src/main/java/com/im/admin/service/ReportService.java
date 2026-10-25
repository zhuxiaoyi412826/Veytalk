package com.im.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.admin.config.AdminProperties;
import com.im.admin.dto.req.ReportAdminQuery;
import com.im.admin.dto.req.ReportHandleRequest;
import com.im.admin.dto.req.ReportSubmitRequest;
import com.im.admin.dto.vo.AdminReportVO;
import com.im.admin.dto.vo.ReportConfigVO;
import com.im.admin.dto.vo.ReportVO;
import com.im.admin.entity.Report;
import com.im.admin.mapper.ReportMapper;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.AdminUserDTO;
import com.im.common.domain.FileDTO;
import com.im.common.domain.PageQuery;
import com.im.common.domain.ReportDisposeCmd;
import com.im.common.domain.ReportDisposeResult;
import com.im.common.domain.ReportTargetDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.MsgType;
import com.im.common.enums.ReportDisposition;
import com.im.common.enums.ReportReason;
import com.im.common.enums.ReportStatus;
import com.im.common.enums.ReportTargetType;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.spi.AdminUserSpi;
import com.im.common.spi.FileStorageSpi;
import com.im.common.spi.PushSpi;
import com.im.common.spi.ReportTargetSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 举报的提交、查询与处置——管理后台一期里唯一「写」的链路。
 *
 * <h2>本模块碰的表只有 {@code im_report} 一张</h2>
 *
 * <p>提交时要抓证据、处置时要封号撤回，这两件都是别的模块的事，一律经
 * {@code com.im.common.spi} 的契约反转（{@code ReportTargetSpi} / {@code AdminUserSpi}）。
 * 于是后台不可能出现「直接 UPDATE im_user 把状态改成 0」这种写了当场能用、
 * 迟早和用户模块的写路径打架的暗依赖。
 *
 * <h2>提交侧的四道闸门，顺序是有讲究的</h2>
 *
 * <p>开关 → 枚举 → 证据解析 → 自举报 → 去重 → 配额。去重和配额放在最后是因为它们最贵
 * （各一次 Redis 写），而前面的校验能挡掉的请求不该消耗配额；
 * <b>去重排在配额之前</b>更要紧：对着同一个人连点二十次是最常见的滥用形态，
 * 若先扣配额，这一通点击会把用户当天的 20 条额度全烧掉，而实际一条都没落库。
 *
 * <h2>为什么处置是「先动手、后落库」</h2>
 *
 * <p>{@link #handle} 先让归属模块执行动作，成功后才把工单写成终态。反过来的话，
 * 一旦动作执行失败（消息已被撤回、房间已经关掉），账上就留下一张「已处置但对象没动」的工单，
 * 而举报人已经收到一句假的「已处理」。现在的顺序最坏是「动作做了、工单还是待处理」，
 * 管理员再点一次即可——所有处置动作都要求幂等，重复点不会造成二次伤害。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    /** 配额键的存活时长：键名已带自然日，TTL 只要比一天长一点就能让 Redis 不被历史键塞满 */
    private static final Duration QUOTA_TTL = Duration.ofHours(48);
    /** 队列一屏最多多少行，超过按此截断（与 ImConstants.MAX_PAGE_SIZE 同口径，这里再夹一次是因为要按行拼用户档案） */
    private static final int ADMIN_PAGE_MAX = 100;

    private final ReportMapper reportMapper;
    private final AdminProperties adminProperties;
    private final RedisUtil redisUtil;
    private final UserQuerySpi userQuerySpi;
    private final SensitiveWordFilter sensitiveWordFilter;
    /** 被举报人的账号状态只能经用户模块取；im-user 缺席时队列仍可读，只是那一列显示为「未知」 */
    private final ObjectProvider<AdminUserSpi> adminUserSpiProvider;
    /** 警告与结果通知都靠长连接，im-websocket 缺席时静默跳过（工单状态照常落库） */
    private final ObjectProvider<PushSpi> pushSpiProvider;
    private final ObjectProvider<ReportTargetSpi> reportTargetSpiProvider;
    /** 证据媒体的字节只能经文件模块取；im-file 缺席时报「存储服务不可用」而不是谎称没这个文件 */
    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;

    /** 对象类型串 → 归属模块的实现，启动时一次性摊平 */
    private final Map<String, ReportTargetSpi> targetResolvers = new HashMap<>();

    /**
     * 建立「哪类对象由哪个模块解析与处置」的索引。
     *
     * <p>同一类型被两个模块认领时直接启动失败：运行期表现为「工单随机由某个模块处置」，
     * 排查成本远高于启动时抛一个异常（与消息模块的内容处理器路由表同一手法）。
     */
    @PostConstruct
    void initTargetResolvers() {
        for (ReportTargetSpi spi : reportTargetSpiProvider.orderedStream().toList()) {
            String type = spi.targetType() == null ? "" : spi.targetType().trim().toLowerCase();
            if (ReportTargetType.of(type) == null) {
                throw new IllegalStateException("举报对象解析器认领了未知类型: " + spi.targetType()
                        + " -> " + spi.getClass().getName());
            }
            ReportTargetSpi previous = targetResolvers.put(type, spi);
            if (previous != null) {
                throw new IllegalStateException("举报对象类型 " + type + " 存在多个实现: "
                        + previous.getClass().getName() + " / " + spi.getClass().getName());
            }
        }
        log.info("[管理后台] 可举报对象类型注册完成: {}", targetResolvers.keySet());
    }

    /* ==================== 字典与开关 ==================== */

    /**
     * 前端需要的三份字典与举报开关，一次给全（设计取舍见 {@link ReportConfigVO} 类注释）。
     */
    public ReportConfigVO config() {
        ReportConfigVO vo = new ReportConfigVO();
        vo.setEnabled(adminProperties.isEnabled());
        vo.setReasonTextMax(adminProperties.getReasonTextMax());
        List<ReportConfigVO.ReasonItem> reasons = new ArrayList<>();
        for (ReportReason reason : ReportReason.values()) {
            reasons.add(new ReportConfigVO.ReasonItem(reason.getCode(), reason.getDesc(), reason.isTextRequired()));
        }
        vo.setReasons(reasons);
        List<ReportConfigVO.Item> types = new ArrayList<>();
        for (ReportTargetType type : ReportTargetType.values()) {
            types.add(new ReportConfigVO.Item(type.getCode(), type.getDesc()));
        }
        vo.setTargetTypes(types);
        List<ReportConfigVO.Item> dispositions = new ArrayList<>();
        for (ReportDisposition disposition : ReportDisposition.values()) {
            dispositions.add(new ReportConfigVO.Item(disposition.getCode(), disposition.getDesc()));
        }
        vo.setDispositions(dispositions);
        return vo;
    }

    /* ==================== 提交（用户侧） ==================== */

    /**
     * 受理一次举报，返回工单 ID。
     *
     * <p>整条链路上唯一「写」的一步是 insert，其余都是校验与 Redis；因此不加事务，
     * 免得 Redis 的去重与配额操作被拖进事务里（它们不参与回滚，失败了只能靠 finally 补删）。
     */
    public Long submit(Long reporterId, ReportSubmitRequest request) {
        BusinessException.throwUnless(adminProperties.isEnabled(), ResultCode.REPORT_DISABLED);

        ReportTargetType type = ReportTargetType.of(request.getTargetType());
        BusinessException.throwIf(type == null, ResultCode.BAD_REQUEST, "不支持的举报对象类型");
        ReportReason reason = ReportReason.of(request.getReasonCode());
        BusinessException.throwIf(reason == null, ResultCode.BAD_REQUEST, "不支持的举报理由");

        String reasonText = normalizeReasonText(request.getReasonText());
        BusinessException.throwIf(reason.isTextRequired() && TextUtil.isBlank(reasonText),
                ResultCode.BAD_REQUEST, "选择「" + reason.getDesc() + "」时必须填写补充描述");

        ReportTargetSpi spi = targetResolvers.get(type.getCode());
        BusinessException.throwIf(spi == null, ResultCode.REPORT_TARGET_NOT_FOUND);

        ReportTargetDTO resolved = spi.resolve(request.getTargetId());
        BusinessException.throwIf(resolved == null, ResultCode.REPORT_TARGET_NOT_FOUND);

        Long reportedUserId = resolved.getReportedUserId();
        boolean self = reporterId.equals(reportedUserId)
                || (type == ReportTargetType.USER && reporterId.equals(request.getTargetId()));
        BusinessException.throwIf(self, ResultCode.REPORT_SELF_FORBIDDEN);

        String dedupKey = RedisKeys.reportDedup(reporterId, type.getCode(), request.getTargetId());
        boolean firstShot = redisUtil.setIfAbsent(dedupKey, String.valueOf(reporterId),
                Duration.ofHours(Math.max(1, adminProperties.getDedupHours())));
        BusinessException.throwUnless(firstShot, ResultCode.REPORT_DUPLICATED);

        // 走到这里意味着去重键已被占用；无论后面哪一步失败，都必须把这个键还回去，
        // 否则用户会被一张根本没落库的工单挡在窗口期外
        boolean inserted = false;
        try {
            consumeQuota(reporterId);
            Report entity = new Report();
            entity.setReporterId(reporterId);
            entity.setTargetType(type.getCode());
            entity.setTargetId(request.getTargetId());
            entity.setReportedUserId(reportedUserId);
            entity.setReasonCode(reason.getCode());
            entity.setReasonText(reasonText);
            entity.setEvidence(buildEvidence(resolved, spi, type, request.getTargetId()));
            entity.setStatus(ReportStatus.PENDING.getCode());
            reportMapper.insert(entity);
            inserted = true;
            log.info("[举报] 已受理工单: id={}, reporter={}, target={}:{}, reason={}",
                    entity.getId(), reporterId, type.getCode(), request.getTargetId(), reason.getCode());
            return entity.getId();
        } finally {
            if (!inserted) {
                redisUtil.delete(dedupKey);
            }
        }
    }

    /**
     * 我自己的举报进展列表，按时间倒序走 {@code idx_reporter_time}。
     *
     * <p>开关 {@code im.admin.report-handler-visible} 打开时，批量解析处置人昵称并填入每行 VO；
     * 关闭时不多加任何查询（默认走一期口径：不给举报人看处置人是谁）。
     */
    public PageResult<ReportVO> mine(Long reporterId, PageQuery query) {
        Page<Report> page = reportMapper.selectPage(query.toPage(), new LambdaQueryWrapper<Report>()
                .eq(Report::getReporterId, reporterId)
                .orderByDesc(Report::getCreateTime)
                .orderByDesc(Report::getId));
        Map<Long, String> handlerNames = resolveHandlerNames(page.getRecords());
        List<ReportVO> vos = page.getRecords().stream()
                .map(r -> toUserVO(r, handlerNames.get(r.getHandlerId())))
                .toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /* ==================== 队列（后台侧） ==================== */

    /**
     * 后台举报队列。筛选条件全部落在有索引的列上，关键词只在两种形态里选一个。
     */
    public PageResult<AdminReportVO> adminPage(ReportAdminQuery query) {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (query.getStatus() != null) {
            BusinessException.throwIf(ReportStatus.of(query.getStatus()) == null,
                    ResultCode.BAD_REQUEST, "未知的工单状态");
            wrapper.eq(Report::getStatus, query.getStatus());
        }
        if (TextUtil.isNotBlank(query.getTargetType())) {
            ReportTargetType type = ReportTargetType.of(query.getTargetType());
            BusinessException.throwIf(type == null, ResultCode.BAD_REQUEST, "不支持的举报对象类型");
            wrapper.eq(Report::getTargetType, type.getCode());
        }
        if (TextUtil.isNotBlank(query.getReasonCode())) {
            ReportReason reason = ReportReason.of(query.getReasonCode());
            BusinessException.throwIf(reason == null, ResultCode.BAD_REQUEST, "不支持的举报理由");
            wrapper.eq(Report::getReasonCode, reason.getCode());
        }
        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            if (keyword.chars().allMatch(Character::isDigit)) {
                long id = parseIdOrThrow(keyword);
                wrapper.and(w -> w.eq(Report::getTargetId, id)
                        .or().eq(Report::getReportedUserId, id)
                        .or().eq(Report::getReporterId, id));
            } else {
                wrapper.like(Report::getReasonText, keyword);
            }
        }
        wrapper.orderByDesc(Report::getCreateTime).orderByDesc(Report::getId);

        Page<Report> page = reportMapper.selectPage(clipPage(query), wrapper);
        List<AdminReportVO> rows = toAdminRows(page.getRecords());
        return PageResult.of(rows, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 单张工单的详情，后台点开某一行时用。
     */
    public AdminReportVO adminDetail(Long reportId) {
        List<AdminReportVO> rows = toAdminRows(List.of(requireReport(reportId)));
        return rows.get(0);
    }

    /**
     * 工单的证据媒体文件（图片 / 视频 / 语音 / 文件消息与通话录制）。
     *
     * <p>为什么不复用 im-file 的 canAccess：那套口径是「上传者或同会话成员」，而管理员
     * 天然两者都不是，判工单偏偏就是必须看到内容的场景。权限门是
     * {@code AdminReportController} 类级的 {@code system:report:handle}，本方法就是
     * 「看证据」这个动作的唯一出口。
     *
     * <p>旧工单的证据里没有结构化的 mediaFileId（该字段后加），但 {@code meta} 里留有
     * 「文件 ID」，由 {@link #compatLegacyMedia} 回捞后照常可看；真取不到才如实说「没有媒体证据」。
     * 闪照销毁后文件记录已逻辑删，降级为「不可取回」而不是谎报。
     */
    public FileDTO mediaOf(Long reportId) {
        Report report = requireReport(reportId);
        ReportTargetDTO evidence = report.getEvidence();
        compatLegacyMedia(evidence);
        BusinessException.throwIf(evidence == null || evidence.getMediaFileId() == null,
                ResultCode.NOT_FOUND, "该工单没有媒体证据");
        FileStorageSpi spi = fileStorageSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
        FileDTO file = spi.getById(evidence.getMediaFileId());
        if (file == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND, "文件已被销毁或删除（闪照查看后即不可取回）");
        }
        return file;
    }

    /** 打开证据媒体的内容流，调用方负责关闭。 */
    public InputStream openMedia(FileDTO file) {
        FileStorageSpi spi = fileStorageSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
        return spi.openStream(file);
    }

    /**
     * 旧工单的证据媒体字段回填（只改响应里的内存对象，不回写库）。
     *
     * <p>背景：{@code mediaFileId/mediaKind} 是后加的结构化字段，此前落库的图片/语音/文件
     * 消息工单里它们为空，后台媒体块就不渲染，只剩 {@code [语音]} 占位文字——可证据的 fileId
     * 其实一直躺在 {@code meta.文件 ID} 里（{@code ReportMessageTargetSpiImpl} 当时就把
     * {@code extra.fileId} 写进了 meta）。这里按键回捞一次，旧工单也能直接看内容。
     *
     * <p>这是「meta 只按键值对展示、不按键取值」约定（见 {@link ReportTargetDTO} 类注释）的
     * <b>唯一例外</b>，且只当结构化字段缺失时才走到：新提交的举报永远直接带 mediaFileId，
     * 不依赖这两个中文键；哪天消息 SPI 改了 meta 键名，受影响的也只是旧工单退化成「没有媒体证据」，
     * 不会拿错文件。
     */
    private void compatLegacyMedia(ReportTargetDTO evidence) {
        if (evidence == null || evidence.getMediaFileId() != null || evidence.getMeta() == null) {
            return;
        }
        long fileId;
        try {
            fileId = Long.parseLong(String.valueOf(evidence.getMeta().get("文件 ID")).trim());
        } catch (RuntimeException e) {
            // 键不存在（get 回 null 拼成 "null"）或不是数字：本来就没什么可回填的，不是异常
            return;
        }
        evidence.setMediaFileId(fileId);
        if (TextUtil.isBlank(evidence.getMediaKind())) {
            String typeDesc = String.valueOf(evidence.getMeta().getOrDefault("消息类型", ""));
            if (MsgType.VOICE.getDesc().equals(typeDesc)) {
                evidence.setMediaKind("audio");
            } else if (MsgType.IMAGE.getDesc().equals(typeDesc)) {
                evidence.setMediaKind("image");
            } else {
                // 文件/通话等旧快照推不出真实类型：给下载链接最稳，不把音频当视频渲染
                evidence.setMediaKind("file");
            }
        }
    }

    /**
     * 受理：把待处理改成「已受理」，只写 {@code handlerId}，不写处理时间。
     *
     * <p>处理时间留给终态，这样「status=1 且 handle_time 为空」就是「有人认领但还没结论」，
     * 不需要为这个中间态再加一列。
     */
    public void accept(Long reportId, Long adminId) {
        Report report = requireReport(reportId);
        ReportStatus current = ReportStatus.of(report.getStatus());
        BusinessException.throwIf(current == null || current != ReportStatus.PENDING,
                ResultCode.BAD_REQUEST, "只有待处理的工单可以受理");
        Report update = new Report();
        update.setId(reportId);
        update.setStatus(ReportStatus.ACCEPTED.getCode());
        update.setHandlerId(adminId);
        reportMapper.updateById(update);
        log.info("[举报] 工单已受理: id={}, admin={}", reportId, adminId);
    }

    /**
     * 驳回：判定举报不成立。与「处置但不动作」的区别见 {@code ReportDisposition#NONE}。
     */
    public String reject(Long reportId, Long adminId, String remark) {
        Report report = requireReport(reportId);
        requireNotFinished(report);
        String cleaned = requireRemark(remark, "驳回必须写明理由，举报人需要知道结论");

        Report update = new Report();
        update.setId(reportId);
        update.setStatus(ReportStatus.REJECTED.getCode());
        // 不写 disposition：这一列只记「真的动了对象」的动作，驳回什么都没动
        // （表注释也是这个口径），写了会让「已驳回」与「已处置但不处理」两种结论在数据里分不清
        update.setHandleRemark(cleaned);
        update.setHandlerId(adminId);
        update.setHandleTime(LocalDateTime.now());
        reportMapper.updateById(update);

        notifyReporter(report, "举报未成立", "经核实「" + reasonName(report.getReasonCode()) + "」不成立：" + cleaned + handlerSignature(adminId), "info");
        log.info("[举报] 工单已驳回: id={}, admin={}", reportId, adminId);
        return "已驳回并通知举报人";
    }

    /**
     * 处置：执行一个动作并把工单置为已处置。
     *
     * @return 给管理员看的一句话（归属模块可能带回补充说明，如「已中断直播并断流」）
     */
    public String handle(Long reportId, Long adminId, ReportHandleRequest request) {
        Report report = requireReport(reportId);
        requireNotFinished(report);

        ReportTargetType type = ReportTargetType.of(report.getTargetType());
        BusinessException.throwIf(type == null, ResultCode.BAD_REQUEST, "工单上的对象类型已不在支持列表内");
        ReportDisposition action = ReportDisposition.of(request.getAction());
        BusinessException.throwIf(action == null, ResultCode.BAD_REQUEST, "不支持的处置动作");
        BusinessException.throwUnless(action.supports(type), ResultCode.BAD_REQUEST,
                "「" + action.getDesc() + "」不能用在" + type.getDesc() + "上");

        String remark = requireRemark(request.getRemark(), "处置必须写明说明（留档并回显给举报人）");

        // 越界动作在落库前就挡住：前端下拉给的是提交那一刻的快照集合，模块后来才装上时
        // 旧工单的下拉会是空的，此时管理员硬塞一个动作只能靠这道校验兜
        List<String> allowed = allowedDispositions(report);
        BusinessException.throwIf(!allowed.contains(action.getCode()), ResultCode.ADMIN_DISPOSITION_UNAVAILABLE);

        Long targetUserId = report.getReportedUserId();
        BusinessException.throwIf(action.isRequiresOwner() && targetUserId == null,
                ResultCode.BAD_REQUEST, "该工单没有可警告的当事人（对象已删除且证据里没留住责任人）");
        // 封掉同行这件事会让后台把自己锁在门外，而且「管理员互相封号」从来不是治理手段
        BusinessException.throwIf(adminId.equals(targetUserId), ResultCode.ADMIN_SELF_FORBIDDEN);

        String disposeMessage = action.local()
                ? executeLocal(action, report, adminId, remark)
                : executeRemote(action, report, type, adminId, remark);

        Report update = new Report();
        update.setId(reportId);
        update.setStatus(ReportStatus.HANDLED.getCode());
        update.setDisposition(action.getCode());
        update.setHandleRemark(remark);
        update.setHandlerId(adminId);
        update.setHandleTime(LocalDateTime.now());
        reportMapper.updateById(update);

        notifyReporter(report, "举报已处理",
                "你的举报已处理，执行动作：「" + action.getDesc() + "」" + (TextUtil.isBlank(remark) ? "" : "；说明：" + remark) + handlerSignature(adminId), "success");
        log.info("[举报] 工单已处置: id={}, target={}:{}, action={}, admin={}",
                reportId, report.getTargetType(), report.getTargetId(), action.getCode(), adminId);
        return TextUtil.isBlank(disposeMessage) ? "已执行「" + action.getDesc() + "」" : disposeMessage;
    }

    /* ==================== 内部 ==================== */

    /**
     * 后台自己就能完成的动作：不处理只改工单，警告当事人则多推一条通知。
     *
     * @return 给管理员看的一句话
     */
    private String executeLocal(ReportDisposition action, Report report, Long adminId, String remark) {
        if (action == ReportDisposition.WARN) {
            Map<String, Object> data = new LinkedHashMap<>(8);
            data.put("action", "admin-warn");
            data.put("title", "内容提醒");
            data.put("message", TextUtil.isBlank(remark) ? "你的内容被用户举报，经平台核实存在违规，请注意规范言行。" : remark);
            data.put("tone", "warning");
            data.put("reportId", report.getId());
            pushQuietly(report.getReportedUserId(), data);
            return "已警告当事人" + (pushOnline(report.getReportedUserId()) ? "" : "（对方当前不在线，下次登录可见）");
        }
        log.info("[举报] 确认但不处置: id={}, admin={}", report.getId(), adminId);
        return "已确认举报内容，本次不执行处置";
    }

    /**
     * 交给归属模块执行的动作。
     *
     * <p>失败原因原样回显（经 {@code BUSINESS_ERROR} 的「业务处理失败：{}」格式化），
     * 管理员看到「消息已被撤回」和「无权执行」是两种完全不同的下一步。
     */
    private String executeRemote(ReportDisposition action, Report report, ReportTargetType type,
                                 Long adminId, String remark) {
        ReportTargetSpi spi = targetResolvers.get(type.getCode());
        BusinessException.throwIf(spi == null, ResultCode.ADMIN_DISPOSITION_UNAVAILABLE);
        ReportDisposeResult result = spi.dispose(ReportDisposeCmd.builder()
                .reportId(report.getId())
                .targetType(type.getCode())
                .targetId(report.getTargetId())
                .reportedUserId(report.getReportedUserId())
                .action(action.getCode())
                .operatorId(adminId)
                .remark(remark)
                .build());
        BusinessException.throwIf(result == null || !result.isSuccess(),
                ResultCode.BUSINESS_ERROR, result == null ? "归属模块未返回处置结果" : result.getMessage());
        return result.getMessage();
    }

    /**
     * 本张工单可执行的动作码：以证据快照里那份为准，缺失时回落到当前 SPI 的能力。
     */
    private List<String> allowedDispositions(Report report) {
        ReportTargetDTO evidence = report.getEvidence();
        if (evidence != null && evidence.getDispositions() != null && !evidence.getDispositions().isEmpty()) {
            return evidence.getDispositions();
        }
        ReportTargetType type = ReportTargetType.of(report.getTargetType());
        if (type == null) {
            return List.of();
        }
        ReportTargetSpi spi = targetResolvers.get(type.getCode());
        // 没有 SPI 时只剩后台自己能做的两个动作：工单不会被卡死在「谁都不能处理」上
        return spi == null
                ? List.of(ReportDisposition.NONE.getCode(), ReportDisposition.WARN.getCode())
                : spi.supportedDispositions();
    }

    private Report requireReport(Long reportId) {
        Report report = reportMapper.selectById(reportId);
        BusinessException.throwIf(report == null, ResultCode.REPORT_NOT_FOUND);
        return report;
    }

    private void requireNotFinished(Report report) {
        BusinessException.throwIf(ReportStatus.isFinished(report.getStatus()), ResultCode.REPORT_ALREADY_HANDLED);
    }

    /**
     * 配额：一天一个自然日键，读值比较后自增。
     *
     * <p>键名带 {@code yyyyMMdd} 而不是靠 TTL 到期，是为了让「重置」严格发生在自然日边界——
     * 用 24 小时滑动窗口，晚上十点开始刷的人可以一直刷到第二天凌晨，那不像「每日限额」。
     * 超额时不回退计数：回退等于让脚本用「失败重试」把配额变成可无限续期的额度。
     */
    private void consumeQuota(Long reporterId) {
        int quota = adminProperties.getDailyQuota();
        if (quota <= 0) {
            return;
        }
        String key = RedisKeys.reportQuota(reporterId, LocalDate.now().toString().replace("-", ""));
        long used = redisUtil.increment(key);
        if (used == 1L) {
            redisUtil.expire(key, QUOTA_TTL);
        }
        BusinessException.throwIf(used > quota, ResultCode.REPORT_QUOTA_EXCEEDED);
    }

    /**
     * 描述文本：清洗脚本片段 → 敏感词遮罩 → 限长。
     *
     * <p>遮罩而不是拒绝，是因为举报人写的脏话往往正是他要举报的内容本身；
     * 拦下来只会让人换个说法再报一次，白白占一份配额。
     */
    private String normalizeReasonText(String raw) {
        String cleaned = TextUtil.sanitize(raw);
        cleaned = sensitiveWordFilter.mask(cleaned);
        cleaned = trimToNull(cleaned);
        if (cleaned == null) {
            return null;
        }
        int max = adminProperties.getReasonTextMax();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private String normalizeRemark(String raw) {
        String cleaned = TextUtil.sanitize(raw);
        cleaned = trimToNull(cleaned);
        if (cleaned == null) {
            return null;
        }
        int max = adminProperties.getReasonTextMax();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    /**
     * 落库前把证据快照整形：类型与 ID 以服务端的值为准（实现方可能回填成别的大小写），
     * 摘要二次截断（契约管不住实现，多一道 substring 换的是工单表不会被一段聊天记录撑爆），
     * 处置动作集合缺失时按当前 SPI 能力补齐。
     */
    private ReportTargetDTO buildEvidence(ReportTargetDTO resolved, ReportTargetSpi spi,
                                          ReportTargetType type, Long targetId) {
        int max = adminProperties.getExcerptMax();
        String excerpt = resolved.getExcerpt();
        if (excerpt != null && max > 0 && excerpt.length() > max) {
            excerpt = excerpt.substring(0, max);
        }
        List<String> dispositions = resolved.getDispositions() == null || resolved.getDispositions().isEmpty()
                ? spi.supportedDispositions()
                : resolved.getDispositions();
        return resolved.toBuilder()
                .targetType(type.getCode())
                .targetId(targetId)
                .excerpt(excerpt)
                .dispositions(dispositions)
                .build();
    }

    private Page<Report> clipPage(ReportAdminQuery query) {
        Page<Report> page = query.toPage();
        if (page.getSize() > ADMIN_PAGE_MAX) {
            page.setSize(ADMIN_PAGE_MAX);
        }
        return page;
    }

    /**
     * 举报人视角的工单：处置人昵称只在开关打开时非空（见 {@link ReportVO} 类注释）。
     */
    private ReportVO toUserVO(Report report, String handlerName) {
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setTargetType(report.getTargetType());
        vo.setTargetTypeName(typeName(report.getTargetType()));
        vo.setTargetId(report.getTargetId());
        ReportTargetDTO evidence = report.getEvidence();
        if (evidence != null) {
            vo.setTargetTitle(evidence.getTitle());
            vo.setTargetExcerpt(evidence.getExcerpt());
        }
        vo.setReasonCode(report.getReasonCode());
        vo.setReasonName(reasonName(report.getReasonCode()));
        vo.setReasonText(report.getReasonText());
        vo.setStatus(report.getStatus());
        vo.setStatusName(ReportStatus.descOf(report.getStatus()));
        vo.setDisposition(report.getDisposition());
        vo.setDispositionName(dispositionName(report.getDisposition()));
        vo.setHandleRemark(report.getHandleRemark());
        vo.setCreateTime(report.getCreateTime());
        vo.setHandleTime(report.getHandleTime());
        vo.setHandlerName(handlerName);
        return vo;
    }

    /**
     * 把工单列表拼成后台行：昵称、账号状态、被举报人的未处置工单数各取一次批量查询，不逐行查。
     */
    private List<AdminReportVO> toAdminRows(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        Set<Long> nameIds = reports.stream()
                .flatMap(r -> Stream.of(r.getReporterId(), r.getReportedUserId(), r.getHandlerId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserBriefDTO> briefs = nameIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(nameIds);

        Set<Long> reportedIds = reports.stream()
                .map(Report::getReportedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Integer> statuses = statusOf(reportedIds);
        Map<Long, Long> pendingCounts = pendingCountsOf(reportedIds);

        List<AdminReportVO> rows = new ArrayList<>(reports.size());
        for (Report report : reports) {
            AdminReportVO vo = new AdminReportVO();
            vo.setId(report.getId());
            vo.setTargetType(report.getTargetType());
            vo.setTargetTypeName(typeName(report.getTargetType()));
            vo.setTargetId(report.getTargetId());
            vo.setReasonCode(report.getReasonCode());
            vo.setReasonName(reasonName(report.getReasonCode()));
            vo.setReasonText(report.getReasonText());
            vo.setStatus(report.getStatus());
            vo.setStatusName(ReportStatus.descOf(report.getStatus()));
            vo.setDisposition(report.getDisposition());
            vo.setDispositionName(dispositionName(report.getDisposition()));
            vo.setHandleRemark(report.getHandleRemark());
            // 旧工单没结构化媒体字段，先从 meta 回捞再交给前端（只改内存，不回写库）
            compatLegacyMedia(report.getEvidence());
            vo.setEvidence(report.getEvidence());
            vo.setAllowedDispositions(allowedDispositions(report));
            vo.setReportedUserId(report.getReportedUserId());
            UserBriefDTO reporter = briefs.get(report.getReporterId());
            if (reporter != null) {
                vo.setReporterName(reporter.getNickname());
                vo.setReporterAvatar(reporter.getAvatar());
            }
            UserBriefDTO reported = briefs.get(report.getReportedUserId());
            if (reported != null) {
                vo.setReportedUserName(reported.getNickname());
            }
            vo.setReportedUserStatus(statuses.get(report.getReportedUserId()));
            vo.setReportedPendingCount(pendingCounts.getOrDefault(report.getReportedUserId(), 0L));
            UserBriefDTO handler = briefs.get(report.getHandlerId());
            if (handler != null) {
                vo.setHandlerName(handler.getNickname());
            }
            vo.setCreateTime(report.getCreateTime());
            vo.setHandleTime(report.getHandleTime());
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 被举报人的账号状态。im-user 未装配（或账号已注销）时结果里就没有那一项，
     * 前端那一列显示「未知」而不是「正常」——把取不到数说成 0 或 1 都会误导处置。
     */
    private Map<Long, Integer> statusOf(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        AdminUserSpi spi = adminUserSpiProvider.getIfAvailable();
        if (spi == null) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>(userIds.size());
        for (AdminUserDTO user : spi.listByIds(userIds)) {
            if (user != null && user.getUserId() != null) {
                result.put(user.getUserId(), user.getStatus());
            }
        }
        return result;
    }

    /**
     * 一次 GROUP BY 取出「这几位各还有几张未处置工单」，用于识别惯犯。
     */
    private Map<Long, Long> pendingCountsOf(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        reportMapper.countPendingByUsers(userIds)
                .forEach(row -> result.put(row.getUserId(), row.getCount()));
        return result;
    }

    /**
     * 把处理结论推回给举报人。
     *
     * <p>{@code tone} 是给前端选通知样式的（info / success），不写进文案也不让前端去猜标题：
     * 靠中文字符串匹配「举报未成立」来决定弹蓝色还是绿色，改一个文案就把样式改乱了。
     */
    private void notifyReporter(Report report, String title, String message, String tone) {
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("action", "report-result");
        data.put("title", title);
        data.put("message", message);
        data.put("tone", tone);
        data.put("reportId", report.getId());
        pushQuietly(report.getReporterId(), data);
    }

    /**
     * 开关关闭时直接返回空表（不碰用户模块）；开关打开时一次批量取回本页处置人的昵称。
     *
     * <p>只为 handlerId 非空的行去查，没处置的工单不产生 ID，也就不参与这次查询。
     */
    private Map<Long, String> resolveHandlerNames(List<Report> reports) {
        if (!adminProperties.isReportHandlerVisible() || reports == null || reports.isEmpty()) {
            return Map.of();
        }
        Set<Long> handlerIds = reports.stream()
                .map(Report::getHandlerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (handlerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>(handlerIds.size());
        userQuerySpi.listByIds(handlerIds).forEach((id, brief) -> {
            if (brief != null && brief.getNickname() != null) {
                names.put(id, brief.getNickname());
            }
        });
        return names;
    }

    /**
     * 处理结论通知的处置人后缀：开关关闭时为空串（文案与一期一致），开启时拼上「（处理人：XX）」。
     *
     * <p>单次查一个昵称可接受：驳回与处置本就是低频写操作，不像列表需要防 N+1。
     */
    private String handlerSignature(Long adminId) {
        if (!adminProperties.isReportHandlerVisible() || adminId == null) {
            return "";
        }
        UserBriefDTO handler = userQuerySpi.getById(adminId);
        String name = handler == null ? null : handler.getNickname();
        return TextUtil.isBlank(name) ? "" : "（处理人：" + name + "）";
    }

    private void pushQuietly(Long userId, Map<String, Object> data) {
        PushSpi spi = pushSpiProvider.getIfAvailable();
        if (spi == null || userId == null) {
            return;
        }
        try {
            spi.pushToUser(userId, WsPacket.of(WsMessageType.NOTIFY, data));
        } catch (Exception e) {
            log.debug("[举报] 通知推送失败，目标可能不在线: userId={}, err={}", userId, e.getMessage());
        }
    }

    private boolean pushOnline(Long userId) {
        PushSpi spi = pushSpiProvider.getIfAvailable();
        return spi != null && userId != null && spi.isUserOnline(userId);
    }

    private String typeName(String code) {
        ReportTargetType type = ReportTargetType.of(code);
        return type == null ? code : type.getDesc();
    }

    private String reasonName(String code) {
        ReportReason reason = ReportReason.of(code);
        return reason == null ? code : reason.getDesc();
    }

    private String dispositionName(String code) {
        if (TextUtil.isBlank(code)) {
            return null;
        }
        ReportDisposition disposition = ReportDisposition.of(code);
        return disposition == null ? code : disposition.getDesc();
    }

    private long parseIdOrThrow(String keyword) {
        try {
            return Long.parseLong(keyword);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "数字关键词超出 ID 范围");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 处置与驳回的说明：按配置决定是否必填，长度与描述列同口径（同为 varchar(500)）。
     */
    private String requireRemark(String remark, String missingMessage) {
        String cleaned = normalizeRemark(remark);
        if (adminProperties.isHandleRemarkRequired()) {
            BusinessException.throwIf(TextUtil.isBlank(cleaned), ResultCode.BAD_REQUEST, missingMessage);
        }
        return cleaned;
    }
}
