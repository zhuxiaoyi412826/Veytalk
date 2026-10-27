package com.im.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.admin.dto.req.AnnouncementQuery;
import com.im.admin.dto.req.AnnouncementSaveRequest;
import com.im.admin.dto.vo.AnnouncementVO;
import com.im.admin.entity.Announcement;
import com.im.admin.mapper.AnnouncementMapper;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.WsPacket;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.spi.PushSpi;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全服公告的编辑、发布、撤回与用户端补拉（四期第四批）。
 *
 * <h2>状态机只有三条边，每条都有人守着</h2>
 *
 * <p>草稿 →（发布）→ 已发布 →（撤回）→ 已撤回。发布只能从草稿发起，撤回只能从已发布发起，
 * 编辑与删除只对草稿开放。这不是保守，是因为「对着全部在线连接喊出去」这个动作没有反悔药：
 * 撤回收不回已经弹在别人屏幕上的那条通知，所以一条公告发出去之后它就是历史，
 * 要改内容只能撤回旧的、再发一条新的（两条都留在库里，谁在什么时候说过什么能被查出来）。
 *
 * <h2>先落库、后广播</h2>
 *
 * <p>与举报处置那条链路（先让归属模块动手、成功才落工单）刻意相反：那里「动作没做成却写了已处置」
 * 会谎报已经处理；而这里库内那一行才是持久事实，广播只是让在线的人少等一次登录。
 * 反过来说，先广播后落库的话，落库一失败就变成「所有人都收到了通知，而后台列表里没有这一条、
 * 之后登录的人也补拉不到」——离线用户与重启后的用户看到的是一份根本不存在的公告。
 * 广播失败只记日志、不改状态：公告仍然留在已发布，等用户下次进来补拉。
 *
 * <h2>正文也要过自己的词库</h2>
 *
 * <p>后台是敏感词过滤的<b>配置方</b>，不是豁免方。不过这一道的话，「把敏感词写进公告」
 * 就成了绕过自己过滤器的正门，而这条内容会被推给每一个在线连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    /** 通知样式白名单，与举报回执的 {@code tone} 同一口径 */
    private static final Set<String> TONES = Set.of("info", "success", "warning", "error");
    private static final String DEFAULT_TONE = "info";
    /** 用户端一次补拉几条：够覆盖「停机三天」，又不至于把历史全塞进本地已读集合 */
    private static final int USER_ACTIVE_LIMIT = 5;

    private final AnnouncementMapper announcementMapper;
    private final SensitiveWordFilter sensitiveWordFilter;
    /** im-websocket 缺席时公告照常落库，只是不实时推送（用户端补拉仍能看到） */
    private final ObjectProvider<PushSpi> pushSpiProvider;

    /* ==================== 后台 ==================== */

    public PageResult<AnnouncementVO> adminPage(AnnouncementQuery query) {
        LambdaQueryWrapper<Announcement> wrapper = Wrappers.<Announcement>lambdaQuery();
        if (query.getStatus() != null) {
            BusinessException.throwIf(!isKnownStatus(query.getStatus()),
                    ResultCode.BAD_REQUEST, "未知的公告状态");
            wrapper.eq(Announcement::getStatus, query.getStatus());
        }
        String keyword = TextUtil.isBlank(query.getKeyword()) ? null : query.getKeyword().trim();
        if (keyword != null) {
            wrapper.and(w -> w.like(Announcement::getTitle, keyword)
                    .or().like(Announcement::getContent, keyword));
        }
        wrapper.orderByDesc(Announcement::getCreateTime).orderByDesc(Announcement::getId);
        Page<Announcement> page = announcementMapper.selectPage(query.toPage(), wrapper);
        List<AnnouncementVO> rows = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(rows, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public AnnouncementVO detail(Long id) {
        return toVO(require(id));
    }

    /**
     * 新建公告，按 {@code publishNow} 决定是否顺手发布。
     *
     * <p>两条路径共用一次 insert：草稿那次只写库；发布那次先写库（状态直接落成已发布、
     * 记下发布时刻），成功后才广播。之所以不「先插草稿再改成已发布」，是因为那中间如果
     * 进程被杀，库里会留下一条永远发不出去的草稿，而管理员那边只看到一次报错。
     */
    public AnnouncementVO save(AnnouncementSaveRequest request, Long operatorId) {
        Announcement entity = new Announcement();
        applyContent(entity, request);
        entity.setStatus(Announcement.STATUS_DRAFT);
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        announcementMapper.insert(entity);

        boolean publishNow = Boolean.TRUE.equals(request.getPublishNow());
        if (!publishNow) {
            log.info("[公告] 已保存草稿: id={}, title={}, 操作人 {}", entity.getId(), entity.getTitle(), operatorId);
            return toVO(entity);
        }
        markPublished(entity, operatorId);
        broadcast(entity);
        return toVO(entity);
    }

    /**
     * 编辑公告，仅草稿可编辑。
     *
     * <p>已发布的不给改：改一条已经广播出去的正文，等于让在线的人看到旧文本、
     * 之后登录的人看到新文本，而两批人都会以为自己是「看到公告的那个人」。要改就撤回旧的再发一条。
     */
    public AnnouncementVO update(Long id, AnnouncementSaveRequest request, Long operatorId) {
        Announcement entity = require(id);
        BusinessException.throwIf(entity.getStatus() != Announcement.STATUS_DRAFT,
                ResultCode.ANNOUNCEMENT_STATE_FORBIDDEN, "只有草稿可以编辑，已发布的公告请撤回后重发");
        applyContent(entity, request);
        entity.setUpdateBy(operatorId);
        announcementMapper.updateById(entity);
        log.info("[公告] 草稿已更新: id={}, title={}, 操作人 {}", id, entity.getTitle(), operatorId);
        return toVO(entity);
    }

    /** 发布一条草稿 */
    public AnnouncementVO publish(Long id, Long operatorId) {
        Announcement entity = require(id);
        BusinessException.throwIf(entity.getStatus() != Announcement.STATUS_DRAFT,
                ResultCode.ANNOUNCEMENT_STATE_FORBIDDEN, "只有草稿可以发布");
        markPublished(entity, operatorId);
        broadcast(entity);
        return toVO(entity);
    }

    /**
     * 撤回：之后登录的人补拉不到它，<b>已经弹出去的那条通知收不回来</b>（已知边界）。
     */
    public AnnouncementVO revoke(Long id, Long operatorId) {
        Announcement entity = require(id);
        BusinessException.throwIf(entity.getStatus() != Announcement.STATUS_PUBLISHED,
                ResultCode.ANNOUNCEMENT_STATE_FORBIDDEN, "只有已发布的公告可以撤回");
        Announcement update = new Announcement();
        update.setId(id);
        update.setStatus(Announcement.STATUS_REVOKED);
        update.setUpdateBy(operatorId);
        announcementMapper.updateById(update);
        entity.setStatus(Announcement.STATUS_REVOKED);
        entity.setUpdateBy(operatorId);
        log.info("[公告] 已撤回: id={}, title={}, 操作人 {}（已推送的通知无法收回）", id, entity.getTitle(), operatorId);
        return toVO(entity);
    }

    /**
     * 删除，仅草稿可删。已发布与已撤回的行留下，它们是「什么时候说过什么话」的台账。
     */
    public void delete(Long id, Long operatorId) {
        Announcement entity = require(id);
        BusinessException.throwIf(entity.getStatus() != Announcement.STATUS_DRAFT,
                ResultCode.ANNOUNCEMENT_STATE_FORBIDDEN, "只有草稿可以删除，已发布的公告请撤回留档");
        announcementMapper.deleteById(id);
        log.info("[公告] 草稿已删除: id={}, title={}, 操作人 {}", id, entity.getTitle(), operatorId);
    }

    /* ==================== 用户端 ==================== */

    /**
     * 当前生效的公告（已发布、未过期），按发布时刻倒序取最新几条。
     *
     * <p>这一条是离线用户的唯一补偿通道：推送是即时的、错过就没有，所以本方法必须存在，
     * 前端在登录与 WebSocket 重连后各拉一次，并按 ID 去重（本地已读集合）。
     *
     * <p>走的是 {@code idx_status_publish}：状态等值 + 发布时刻倒序，正好是索引的前导列加排序列。
     */
    public List<AnnouncementVO> activeForUser() {
        LocalDateTime now = LocalDateTime.now();
        List<Announcement> rows = announcementMapper.selectPage(
                        Page.of(1, USER_ACTIVE_LIMIT, false),
                        Wrappers.<Announcement>lambdaQuery()
                                .eq(Announcement::getStatus, Announcement.STATUS_PUBLISHED)
                                .and(w -> w.isNull(Announcement::getExpireTime).or().gt(Announcement::getExpireTime, now))
                                .orderByDesc(Announcement::getPublishTime)
                                .orderByDesc(Announcement::getId))
                .getRecords();
        return rows.stream().map(this::toVO).toList();
    }

    /* ==================== 内部 ==================== */

    /** 落库正文：清洗脚本片段 → 敏感词遮罩 → 标题与正文各自限长 */
    private void applyContent(Announcement entity, AnnouncementSaveRequest request) {
        String title = TextUtil.sanitize(request.getTitle());
        String content = TextUtil.sanitize(request.getContent());
        entity.setTitle(trimAndCut(sensitiveWordFilter.mask(title), 100));
        entity.setContent(sensitiveWordFilter.mask(content));
        entity.setTone(normalizeTone(request.getTone()));
        LocalDateTime expireTime = request.getExpireTime();
        BusinessException.throwIf(expireTime != null && !expireTime.isAfter(LocalDateTime.now()),
                ResultCode.BAD_REQUEST, "失效时间必须晚于当前时间，留空表示不失效");
        entity.setExpireTime(expireTime);
    }

    private void markPublished(Announcement entity, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        Announcement update = new Announcement();
        update.setId(entity.getId());
        update.setStatus(Announcement.STATUS_PUBLISHED);
        update.setPublishTime(now);
        update.setUpdateBy(operatorId);
        announcementMapper.updateById(update);
        entity.setStatus(Announcement.STATUS_PUBLISHED);
        entity.setPublishTime(now);
        entity.setUpdateBy(operatorId);
    }

    /**
     * 广播一帧 {@code notify}，{@code action=announce} 让用户端把它认成公告而不是普通通知。
     *
     * <p>不新增 {@code WsMessageType}：{@code notify} 早就是「按 data.action 分流」的形状
     * （connected / report-result / admin-warn / 群事件），为一个新动作再开一种帧类型，
     * 用户端就要多认一个入口，而它本来已经有四个分支了。
     *
     * <p>{@code PushSpi.broadcast} 返回 void，所以后台不显示「N 人收到」——
     * 拿在线用户数凑这个数字会是假数。
     */
    private void broadcast(Announcement entity) {
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("action", "announce");
        data.put("id", entity.getId());
        data.put("title", entity.getTitle());
        data.put("message", entity.getContent());
        data.put("tone", entity.getTone());
        PushSpi spi = pushSpiProvider.getIfAvailable();
        if (spi == null) {
            log.warn("[公告] 推送通道不可用（im-websocket 未装配），公告只落库：id={}", entity.getId());
            return;
        }
        try {
            spi.broadcast(WsPacket.of(WsMessageType.NOTIFY, data));
            log.info("[公告] 已发布并广播: id={}, title={}", entity.getId(), entity.getTitle());
        } catch (Exception e) {
            // 不改状态：公告仍是已发布，离线与之后登录的人靠补拉拿到，比把它退回草稿更贴近事实
            log.error("[公告] 广播失败，公告已落库为已发布（用户端仍可靠补拉拿到）: id={}", entity.getId(), e);
        }
    }

    private Announcement require(Long id) {
        Announcement entity = announcementMapper.selectById(id);
        BusinessException.throwIf(entity == null, ResultCode.ANNOUNCEMENT_NOT_FOUND);
        return entity;
    }

    private boolean isKnownStatus(Integer status) {
        return status != null && (status == Announcement.STATUS_DRAFT
                || status == Announcement.STATUS_PUBLISHED
                || status == Announcement.STATUS_REVOKED);
    }

    private String normalizeTone(String tone) {
        if (TextUtil.isBlank(tone)) {
            return DEFAULT_TONE;
        }
        String cleaned = tone.trim().toLowerCase();
        BusinessException.throwIf(!TONES.contains(cleaned), ResultCode.BAD_REQUEST,
                "通知样式只能是 info / success / warning / error");
        return cleaned;
    }

    /**
     * 标题收口：注解已经限过 100，这里再剪一次是因为清洗与遮罩都可能改变长度，
     * 而 {@code im_announcement.title} 的列宽是服务端唯一该守的边界。
     */
    private String trimAndCut(String value, int max) {
        String cleaned = value == null ? "" : value.trim();
        BusinessException.throwIf(cleaned.isEmpty(), ResultCode.BAD_REQUEST, "公告标题不能为空");
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private AnnouncementVO toVO(Announcement entity) {
        AnnouncementVO vo = new AnnouncementVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setTone(entity.getTone());
        vo.setStatus(entity.getStatus());
        vo.setStatusName(statusName(entity.getStatus()));
        vo.setPublishTime(entity.getPublishTime());
        vo.setExpireTime(entity.getExpireTime());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateBy(entity.getUpdateBy());
        return vo;
    }

    private String statusName(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case Announcement.STATUS_DRAFT -> "草稿";
            case Announcement.STATUS_PUBLISHED -> "已发布";
            case Announcement.STATUS_REVOKED -> "已撤回";
            default -> "未知(" + status + ")";
        };
    }
}

