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
                .map(row -> PublicReleaseVO.builder()
                        .id(row.getId())
                        .platform(row.getPlatform())
                        .platformName(platformName(row.getPlatform()))
                        .version(row.getVersion())
                        .releaseNote(row.getReleaseNote())
                        .fileName(row.getFileName())
                        .fileSize(row.getFileSize())
                        .md5(row.getMd5())
                        .updateTime(row.getUpdateTime())
                        .downloadPath("/api/release/download/" + row.getId())
                        .build())
                .toList();
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

