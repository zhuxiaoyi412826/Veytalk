package com.im.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.admin.dto.req.ReleaseQuery;
import com.im.admin.dto.req.ReleaseSaveRequest;
import com.im.admin.dto.vo.PublicReleaseVO;
import com.im.admin.dto.vo.ReleaseVO;
import com.im.admin.entity.ClientRelease;
import com.im.admin.enums.ReleasePlatform;
import com.im.admin.mapper.ClientReleaseMapper;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.FileDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FileStorageSpi;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端版本管理：后台 CRUD + 启用互斥，以及官网下载页的公开读与安装包流式输出。
 *
 * <p>表归 im-admin 所有（与公告同例），但安装包字节一律经 {@link FileStorageSpi} 交给
 * 文件模块：本类不 import 任何 im-file 的类、不拼对象键，「字节在哪、怎么存」
 * 仍然是文件模块一家的事（MinIO 与本地磁盘可切换，切换时本表一行不用改）。
 *
 * <p><b>启用互斥</b>落在 {@link #enable} 的同一事务里：先把同平台所有启用行压成停用，
 * 再把本行立起来。两步拆开（先 disable 旧的再 enable 新的）会留下一个
 * 「该平台 momentarily 没有任何启用行」的窗口，官网下载页在这个窗口里会缺一张卡——
 * 访客看不到错误、只看到「下载按钮没了」，比报错更难解释。
 *
 * <p><b>公开下载的安全口径</b>：匿名端点只认「启用中的行」，停用行与不存在的 ID 一律
 * 404 同待遇（不回「已停用」——那等于告诉探测者这个 ID 曾经存在）；字节出口不复用
 * {@code /api/file/download}（那条要登录态或票据），由 {@code ReleaseController} 自己写响应头流式输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientReleaseService {

    /** 单页上限：一行只带元数据不带正文，但安装包列表没必要一屏百行，与公告同档 */
    private static final long MAX_PAGE_SIZE = 50;

    /**
     * 历史版本每平台默认取几条。五档平台 × 5 = 至多 25 行，官网一个区块够用又不至于把页面拉长；
     * 上限 20 是给「一个平台发过很多次」的场景留的口，但不是让访客拿它拖全表。
     */
    private static final int HISTORY_PER_PLATFORM = 5;
    private static final int HISTORY_PER_PLATFORM_MAX = 20;

    private final ClientReleaseMapper releaseMapper;
    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;

    /* ==================== 后台 ==================== */

    /**
     * 后台列表：启用中的恒排最前（同看「现在官网递出去的是哪几行」），其余按创建时间倒序。
     */
    public PageResult<ReleaseVO> adminPage(ReleaseQuery query) {
        long current = Math.max(1, query.safeCurrent());
        long size = Math.min(Math.max(1, query.safeSize()), MAX_PAGE_SIZE);
        ReleasePlatform platform = TextUtil.isNotBlank(query.getPlatform())
                ? ReleasePlatform.of(query.getPlatform()) : null;
        // 传了不认识的平台码不当作「查全部」：那会让一个筛错的调用看到全表并以为筛对了
        BusinessException.throwIf(TextUtil.isNotBlank(query.getPlatform()) && platform == null,
                ResultCode.BAD_REQUEST, "未知的平台码：" + query.getPlatform());
        String keyword = trimToNull(query.getKeyword());

        Page<ClientRelease> page = releaseMapper.selectPage(Page.of(current, size),
                Wrappers.<ClientRelease>lambdaQuery()
                        .eq(platform != null, ClientRelease::getPlatform, platform == null ? null : platform.getCode())
                        .eq(query.getStatus() != null, ClientRelease::getStatus, query.getStatus())
                        .and(keyword != null, w -> w.like(ClientRelease::getVersion, keyword)
                                .or().like(ClientRelease::getFileName, keyword))
                        .orderByDesc(ClientRelease::getStatus)
                        .orderByDesc(ClientRelease::getCreateTime)
                        .orderByDesc(ClientRelease::getId));
        List<ReleaseVO> records = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public ReleaseVO detail(Long id) {
        return toVO(require(id));
    }

    /**
     * 新建版本行。{@code enableNow=true} 时同一事务里顺带完成启用互斥。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReleaseVO create(ReleaseSaveRequest request, Long operatorId) {
        ReleasePlatform platform = ReleasePlatform.of(request.getPlatform());
        BusinessException.throwIf(platform == null, ResultCode.BAD_REQUEST,
                "未知的平台码：" + request.getPlatform());
        String version = request.getVersion().trim();
        BusinessException.throwIf(releaseMapper.exists(Wrappers.<ClientRelease>lambdaQuery()
                        .eq(ClientRelease::getPlatform, platform.getCode())
                        .eq(ClientRelease::getVersion, version)),
                ResultCode.BAD_REQUEST, platform.getDisplayName() + " 已存在版本 " + version + "，同平台版本号不可重复");

        FileDTO file = requireFile(request.getFileId());
        ClientRelease row = new ClientRelease();
        row.setPlatform(platform.getCode());
        row.setVersion(version);
        row.setReleaseNote(trimToNull(request.getReleaseNote()));
        row.setFileId(file.getFileId());
        row.setFileName(file.getOriginalName());
        row.setFileSize(file.getSize());
        row.setMd5(file.getMd5());
        row.setStatus(ClientRelease.STATUS_DISABLED);
        row.setDownloadCount(0L);
        row.setCreateBy(operatorId);
        row.setUpdateBy(operatorId);
        releaseMapper.insert(row);
        log.info("[客户端版本] 新建: id={}, platform={}, version={}, fileId={}, operator={}",
                row.getId(), row.getPlatform(), row.getVersion(), row.getFileId(), operatorId);
        if (Boolean.TRUE.equals(request.getEnableNow())) {
            return enable(row.getId(), operatorId);
        }
        return toVO(row);
    }

    /**
     * 编辑：版本号 / 更新说明 / 安装包（换包）可改，平台不可改（见入参类注释）。
     * 启用中的行允许换包——「发现包坏了立刻换一个」正是启用态最需要的操作，
     * 要求先停用再换再启用会把官网下载口空转两次。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReleaseVO update(Long id, ReleaseSaveRequest request, Long operatorId) {
        ClientRelease row = require(id);
        String version = request.getVersion().trim();
        BusinessException.throwIf(releaseMapper.exists(Wrappers.<ClientRelease>lambdaQuery()
                        .eq(ClientRelease::getPlatform, row.getPlatform())
                        .eq(ClientRelease::getVersion, version)
                        .ne(ClientRelease::getId, id)),
                ResultCode.BAD_REQUEST, "该平台已存在版本 " + version + "，同平台版本号不可重复");
        FileDTO file = requireFile(request.getFileId());
        row.setVersion(version);
        row.setReleaseNote(trimToNull(request.getReleaseNote()));
        row.setFileId(file.getFileId());
        row.setFileName(file.getOriginalName());
        row.setFileSize(file.getSize());
        row.setMd5(file.getMd5());
        row.setUpdateBy(operatorId);
        releaseMapper.updateById(row);
        log.info("[客户端版本] 编辑: id={}, version={}, fileId={}, operator={}", id, version, file.getFileId(), operatorId);
        return toVO(row);
    }

    /**
     * 启用：同平台原启用行自动停用。启用前确认字节真的还在存储里——
     * 把一条指向缺失对象的行立成官网唯一下载口，等于对着公网挂一个必坏的链接。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReleaseVO enable(Long id, Long operatorId) {
        ClientRelease row = require(id);
        requireFile(row.getFileId());
        if (row.getStatus() != null && row.getStatus() == ClientRelease.STATUS_ENABLED) {
            return toVO(row);
        }
        releaseMapper.update(null, Wrappers.<ClientRelease>lambdaUpdate()
                .set(ClientRelease::getStatus, ClientRelease.STATUS_DISABLED)
                .set(ClientRelease::getUpdateBy, operatorId)
                .eq(ClientRelease::getPlatform, row.getPlatform())
                .eq(ClientRelease::getStatus, ClientRelease.STATUS_ENABLED));
        row.setStatus(ClientRelease.STATUS_ENABLED);
        row.setUpdateBy(operatorId);
        releaseMapper.updateById(row);
        log.info("[客户端版本] 启用: id={}, platform={}, version={}, operator={}",
                id, row.getPlatform(), row.getVersion(), operatorId);
        return toVO(row);
    }

    /** 停用：官网该平台即刻没有下载口（卡片消失），是「下线一个坏包」的止损动作。 */
    @Transactional(rollbackFor = Exception.class)
    public ReleaseVO disable(Long id, Long operatorId) {
        ClientRelease row = require(id);
        if (row.getStatus() != null && row.getStatus() == ClientRelease.STATUS_DISABLED) {
            return toVO(row);
        }
        row.setStatus(ClientRelease.STATUS_DISABLED);
        row.setUpdateBy(operatorId);
        releaseMapper.updateById(row);
        log.info("[客户端版本] 停用: id={}, platform={}, version={}, operator={}",
                id, row.getPlatform(), row.getVersion(), operatorId);
        return toVO(row);
    }

    /**
     * 删除：物理删行，仅停用态可删（启用中的先停用，见实体类注释）。
     * 不删存储字节：同 MD5 的对象可能被别的行秒传复用，删字节会连坐。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        ClientRelease row = require(id);
        BusinessException.throwIf(row.getStatus() != null && row.getStatus() == ClientRelease.STATUS_ENABLED,
                ResultCode.BAD_REQUEST, "启用中的版本不能删除，请先停用（官网下载页正在递出这个包）");
        releaseMapper.deleteById(id);
        log.info("[客户端版本] 删除: id={}, platform={}, version={}, operator={}",
                id, row.getPlatform(), row.getVersion(), operatorId);
    }

    /* ==================== 官网公开 ==================== */

    /**
     * 官网下载页数据源：启用中的行，按平台码表顺序排（Windows 安装版恒在第一张卡）。
     */
    public List<PublicReleaseVO> publicList() {
        List<ClientRelease> enabled = releaseMapper.selectList(Wrappers.<ClientRelease>lambdaQuery()
                .eq(ClientRelease::getStatus, ClientRelease.STATUS_ENABLED));
        return enabled.stream()
                .sorted((a, b) -> platformOrder(a.getPlatform()) - platformOrder(b.getPlatform()))
                .map(this::toPublicVO)
                .toList();
    }

    /**
     * 官网「历史版本」区块数据源：每平台取最近 N 条（含已停用），按创建时间倒序。
     *
     * <p>与 {@link #publicList()} 的分工：list 是「现在能下到的包」（每平台一条、只认启用行），
     * history 是「发过哪些版本」。停用行的 {@code downloadPath} 一律为 null ——
     * {@code /api/release/download/{id}} 只递启用行，给了链接访客点了必 404。
     *
     * <p>★ 这里确实把「某个停用版本存在过」公开了，与下载端点那条防枚举口径不矛盾：
     * 那条守的是<b>字节</b>（拿到 ID 也只能拿到 404），不是版本号；而历史包本来就
     * 在 GitHub Releases 页上公开摆着（官网下载区下面就是那条外链），藏着没有收益。
     *
     * <p>排序取 createTime 而不是 version：实体注释已定下「版本号不做语义比较，1.10 与 1.9
     * 谁新由发版的人知道、代码不猜」，按时间倒序是同一个口径的另一面。
     *
     * <p>每平台各取 N 条而不是一条全局 limit：后者会让发版勤的那个平台把其余平台挤没；
     * 而平台最多五档，五次带索引的查询比一次窗口函数划算。
     *
     * @param platformCode 平台码，为空则每档平台都取；不认识的平台码回空数组而不是全表
     *                     （与 {@code adminPage} 「筛错了不许看到全部」同一条口径）
     * @param perPlatform  每平台条数，null/非正取默认 5，上限 20
     */
    public List<PublicReleaseVO> publicHistory(String platformCode, Integer perPlatform) {
        int limit = perPlatform == null || perPlatform <= 0
                ? HISTORY_PER_PLATFORM : Math.min(perPlatform, HISTORY_PER_PLATFORM_MAX);
        List<ReleasePlatform> targets;
        if (TextUtil.isNotBlank(platformCode)) {
            ReleasePlatform platform = ReleasePlatform.of(platformCode);
            if (platform == null) {
                return List.of();
            }
            targets = List.of(platform);
        } else {
            targets = List.of(ReleasePlatform.values());
        }
        // 按枚举声明顺序遍历，拼出来的次序天然就是码表顺序（Windows 安装版在最前），不必再排一次
        List<PublicReleaseVO> rows = new ArrayList<>();
        for (ReleasePlatform platform : targets) {
            List<ClientRelease> list = releaseMapper.selectList(Wrappers.<ClientRelease>lambdaQuery()
                    .eq(ClientRelease::getPlatform, platform.getCode())
                    .orderByDesc(ClientRelease::getCreateTime)
                    .orderByDesc(ClientRelease::getId)
                    .last("LIMIT " + limit));
            list.forEach(row -> rows.add(toPublicVO(row)));
        }
        return rows;
    }

    /**
     * 实体 → 官网公开视图。停用行刻意不给 downloadPath：
     * 公开下载只认启用行，链子递出去就是一个必 404 的死按钮，不如让前端渲染成「已停用」。
     */
    private PublicReleaseVO toPublicVO(ClientRelease row) {
        boolean enabled = row.getStatus() != null && row.getStatus() == ClientRelease.STATUS_ENABLED;
        return PublicReleaseVO.builder()
                .id(row.getId())
                .platform(row.getPlatform())
                .platformName(platformName(row.getPlatform()))
                .version(row.getVersion())
                .releaseNote(row.getReleaseNote())
                .fileName(row.getFileName())
                .fileSize(row.getFileSize())
                .md5(row.getMd5())
                .updateTime(row.getUpdateTime())
                .enabled(enabled)
                .downloadPath(enabled ? "/api/release/download/" + row.getId() : null)
                .build();
    }

    /**
     * 公开下载的行校验：只认启用中的行。停用与不存在回同一种错（NOT_FOUND），
     * 不让探测者凭响应区分「曾经有过」与「从来没有」。
     */
    public ClientRelease requireDownloadable(Long id) {
        ClientRelease row = require(id);
        BusinessException.throwIf(row.getStatus() == null || row.getStatus() != ClientRelease.STATUS_ENABLED,
                ResultCode.NOT_FOUND, "客户端版本不存在");
        return row;
    }

    /** 取安装包元数据；文件行已消失（被人工清过）时据实报「不存在」，不编一个空 DTO。 */
    public FileDTO requireFileOf(ClientRelease row) {
        return requireFile(row.getFileId());
    }

    /** 打开安装包内容流，调用方负责关闭。 */
    public InputStream openStream(FileDTO file) {
        FileStorageSpi spi = requireStorage();
        InputStream in = spi.openStream(file);
        BusinessException.throwIf(in == null, ResultCode.FILE_NOT_FOUND);
        return in;
    }

    /** 下载次数 +1：失败只记日志不影响下载本身（计数是运营数字，不是账）。 */
    public void countDownload(Long id) {
        try {
            releaseMapper.update(null, Wrappers.<ClientRelease>lambdaUpdate()
                    .setSql("download_count = download_count + 1")
                    .eq(ClientRelease::getId, id));
        } catch (Exception e) {
            log.warn("[客户端版本] 下载次数累加失败: id={}", id, e);
        }
    }

    /* ==================== 内部 ==================== */

    private ClientRelease require(Long id) {
        BusinessException.throwIf(id == null, ResultCode.BAD_REQUEST, "缺少版本 ID");
        ClientRelease row = releaseMapper.selectById(id);
        BusinessException.throwIf(row == null, ResultCode.NOT_FOUND, "客户端版本不存在");
        return row;
    }

    private FileDTO requireFile(Long fileId) {
        BusinessException.throwIf(fileId == null, ResultCode.BAD_REQUEST, "缺少安装包文件 ID");
        FileStorageSpi spi = requireStorage();
        FileDTO file = spi.getById(fileId);
        BusinessException.throwIf(file == null, ResultCode.FILE_NOT_FOUND);
        return file;
    }

    private FileStorageSpi requireStorage() {
        FileStorageSpi spi = fileStorageSpiProvider.getIfAvailable();
        BusinessException.throwIf(spi == null, ResultCode.FILE_STORAGE_UNAVAILABLE);
        return spi;
    }

    private ReleaseVO toVO(ClientRelease row) {
        ReleasePlatform platform = ReleasePlatform.of(row.getPlatform());
        // filePresent 只作展示提示，文件模块缺席时不阻断列表本身
        FileStorageSpi spi = fileStorageSpiProvider.getIfAvailable();
        FileDTO file = (row.getFileId() == null || spi == null) ? null : spi.getById(row.getFileId());
        return ReleaseVO.builder()
                .id(row.getId())
                .platform(row.getPlatform())
                .platformName(platform == null ? row.getPlatform() : platform.getDisplayName())
                .version(row.getVersion())
                .releaseNote(row.getReleaseNote())
                .fileId(row.getFileId())
                .fileName(row.getFileName())
                .fileSize(row.getFileSize())
                .md5(row.getMd5())
                .status(row.getStatus())
                .statusName(row.getStatus() != null && row.getStatus() == ClientRelease.STATUS_ENABLED
                        ? "启用中" : "已停用")
                .filePresent(file != null)
                .downloadCount(row.getDownloadCount() == null ? 0L : row.getDownloadCount())
                .createBy(row.getCreateBy())
                .updateBy(row.getUpdateBy())
                .createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime())
                .build();
    }

    private int platformOrder(String code) {
        ReleasePlatform platform = ReleasePlatform.of(code);
        return platform == null ? ReleasePlatform.values().length : platform.ordinal();
    }

    private String platformName(String code) {
        ReleasePlatform platform = ReleasePlatform.of(code);
        return platform == null ? code : platform.getDisplayName();
    }

    /** TextUtil 没有 trimToNull，这里自留一个：空串归一成 null，让「说明留空」在库里是 NULL 而不是 ''。 */
    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

