package com.im.file.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.api.ResultCode;
import com.im.common.domain.UploadCmd;
import com.im.common.exception.BusinessException;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.file.dto.req.ModerationReportReq;
import com.im.file.dto.vo.ModerationPolicyVO;
import com.im.file.enums.FileBizType;
import com.im.file.service.ModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 图片内容审核接口。
 *
 * <p>挂在 {@code /api/file/moderation} 而不是单开一个 {@code /api/moderation}：
 * 审核属于「图片进本站」这一段的事，与文件域同源同生命周期；
 * 更实际的理由是 Knife4j 的分组按路径前缀切（{@code /api/file/**}），
 * 单开前缀会让这两个端点从接口文档里凭空消失——文档丢了不比接口坏了更难发现。
 *
 * <p>{@code /report} 的配额故意很松（每分钟 30 次）：命中上报是低频事件，
 * 而它一旦被限流掐掉，丢的是审计记录本身。前端对这个请求是尽力而为，
 * 失败不重试，所以这里也不需要为了防止刷爆而卡得很死。
 *
 * <p>{@code /evidence} 不标 {@code PERM_FILE_UPLOAD}：权限点管的是「能不能往本站传业务文件」，
 * 而这一头是取证的旁路写入，真正的门是服务层那三道（登录态 + 记录归属 + 体积上限）。
 * 拿上传权限去挡，一个没获得上传配额的人就连证据也递不进来，那是把两件无关的事绑在一起。
 */
@Slf4j
@Tag(name = "06-文件审核", description = "图片内容审核：策略下发、发送端命中上报与隔离区证据投递")
@RestController
@RequestMapping("/api/file/moderation")
@RequiredArgsConstructor
public class ModerationController {

    private final ModerationService moderationService;

    @Operation(summary = "审核策略", description = "返回总开关、阈值与隔离区参数，发送端据此决定要不要识别、按什么判违规、要不要传副本")
    @SaCheckLogin
    @GetMapping("/policy")
    public Result<ModerationPolicyVO> policy() {
        return Result.ok(moderationService.policy());
    }

    @Operation(summary = "命中上报", description = "发送端识别到违规图片并拒发后上报判定结果，只落审计通道，服务端不采信其结论；"
            + "返回流水 ID，发送端据此把图片副本挂到这条记录上（回 null 就不要再传）")
    @SaCheckLogin
    @RateLimit(count = 30, dimension = RateLimit.Dimension.USER, key = "file.moderation.report")
    @PostMapping("/report")
    public Result<Long> report(@Valid @RequestBody ModerationReportReq req) {
        return Result.ok(moderationService.report(SecurityUtil.getUserId(), req));
    }

    @Operation(summary = "上传隔离区证据", description = "把被拦下的原图另传一份到隔离区并挂到指定命中记录上；"
            + "只能挂到自己那条记录、一条记录只挂一次（重复投递幂等）、体积不过隔离区上限")
    @SaCheckLogin
    @RateLimit(count = 20, dimension = RateLimit.Dimension.USER, key = "file.moderation.evidence")
    @PostMapping("/evidence/{logId}")
    public Result<Void> evidence(@Parameter(description = "report 回到的流水 ID") @PathVariable("logId") Long logId,
                                 @RequestPart("file") MultipartFile file) {
        moderationService.attachEvidence(logId, toCmd(SecurityUtil.getUserId(), file));
        return Result.ok();
    }

    /**
     * 把 {@link MultipartFile} 搬进 {@link UploadCmd}。
     *
     * <p>与 {@code FileController#toCmd} 同构，但 {@code bizType} 不从这里开始参与：
     * 服务层会把它固定成 {@code moderation_quarantine}，所以这里连一个类型参数都不接。
     * 上限也不在这里判：隔离区那个尺寸归 {@code im.moderation.quarantine-max-size}，
     * 普通上传的 {@code im.file.max-size} 由 {@code FileService#store} 兜底，两处规则不各自演化。
     */
    private UploadCmd toCmd(Long userId, MultipartFile file) {
        BusinessException.throwIf(file == null || file.isEmpty(), ResultCode.FILE_EMPTY);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("[审核] 读取证据副本失败: name={}, size={}", file.getOriginalFilename(), file.getSize(), e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
        return UploadCmd.builder()
                .uploaderId(userId)
                .bizType(FileBizType.MODERATION_QUARANTINE.getCode())
                .originalName(TextUtil.isBlank(file.getOriginalFilename()) ? "evidence.jpg" : file.getOriginalFilename())
                .contentType(file.getContentType())
                .size((long) bytes.length)
                .bytes(bytes)
                .build();
    }
}
