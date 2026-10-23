package com.im.ai.intelligence;

import com.im.ai.service.AudioService;
import com.im.common.api.ResultCode;
import com.im.common.domain.FileDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FileStorageSpi;
import com.im.common.spi.SpeechToTextSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;

/**
 * 聊天消息「转文字」的真实 ASR 实现：复用面试「按住说话」那一条百炼链路。
 *
 * <p>为什么实现在 im-ai 而不是 im-message：模型凭据（ALI_BABA_API_KEY）、请求体格式、
 * 超时与容器名收敛都在 {@link AudioService} 里，im-message 只依赖 im-common，
 * 把它拉过去等于让消息模块长出 AI 依赖。im-ai 只依赖 im-common，实现
 * {@link SpeechToTextSpi} 后由 im-message 的 {@code ObjectProvider} 在同一个 Spring
 * 容器里取走——编译期干净、运行期自动接通，跨模块协作仍是走 SPI 契约。
 *
 * <p>为什么不直接用消息里存的 fileUrl：受控下载地址要登录态（服务端自己 http 回环拿不到），
 * 预签名直链又有时效。所以走 {@link FileStorageSpi} 按 fileId 读字节，MinIO 与本地磁盘
 * 两种存储都不绕公网。前端约定见 ChatWindow 的「转文字」菜单项。
 *
 * <p>{@code @Primary} 保证 {@code im.intelligence.mock-when-unconfigured=true} 的开发形态下
 * 也优先用真实识别，内置桩只在「没配 key」时兜底展示链路。
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class DashScopeSpeechToTextSpi implements SpeechToTextSpi {

    /** 与 AudioService 的 ASR 输入上限一致：超过就明确拒掉，不能把整 bucket 读进内存 */
    private static final long MAX_AUDIO_BYTES = 4L * 1024 * 1024;

    private final AudioService audioService;
    /** im-file 未装配时（单模块启动的开发形态）取不到 bean，直接降级而不是空指针 */
    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;

    @Override
    public boolean available() {
        return audioService.available();
    }

    /**
     * 内置桩与老调用方用的地址入口：本实现只认 fileId，没有 ID 就只能返回空串让调用方降级
     * （拿 URL 去请求要么缺登录态、要么已过期，硬解出来反而更难排查）。
     */
    @Override
    public String toText(String audioUrl, Integer durationSeconds) {
        log.warn("聊天转文字缺少 fileId，无法按存储直读音频，已降级");
        return "";
    }

    @Override
    public String toText(Long fileId, String audioUrl, Integer durationSeconds) {
        if (fileId == null) {
            return toText(audioUrl, durationSeconds);
        }
        FileStorageSpi storage = fileStorageSpiProvider.getIfAvailable();
        if (storage == null) {
            log.warn("文件存储未装配，聊天转文字不可用: fileId={}", fileId);
            return "";
        }
        FileDTO file = storage.getById(fileId);
        if (file == null) {
            // 闪照销毁或记录已删：内容本来就不该再被看到，按「识别不出文本」降级
            log.info("聊天转文字找不到文件记录: fileId={}", fileId);
            return "";
        }
        if (file.getSize() != null && file.getSize() > MAX_AUDIO_BYTES) {
            throw new BusinessException(ResultCode.AI_ASR_FAILED.getCode(), "语音过长（上限 4MB），暂不支持转文字");
        }
        byte[] bytes;
        try (InputStream in = storage.openStream(file)) {
            if (in == null) {
                log.warn("文件存储返回空流: fileId={}", fileId);
                return "";
            }
            bytes = in.readAllBytes();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取语音字节失败: fileId={}, {}", fileId, e.getMessage());
            throw new BusinessException(ResultCode.AI_ASR_FAILED.getCode(), "语音文件读取失败，请稍后重试");
        }
        // 容器名以文件记录为准：用户手选的音频可能是 mp3/m4a，谎称 wav 模型会直接回错误
        return audioService.transcribe(bytes, resolveFormat(file));
    }

    /**
     * 先信 ext（上传时按原始名落的），ext 缺失再按 MIME 前缀兜底；都不认识就交给
     * {@code AudioService} 收敛成 wav。
     */
    private String resolveFormat(FileDTO file) {
        String ext = file.getExt();
        if (ext != null && !ext.isBlank()) {
            return ext.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        }
        String contentType = file.getContentType();
        if (contentType == null) {
            return "wav";
        }
        String mime = contentType.toLowerCase(Locale.ROOT);
        if (mime.contains("mpeg") || mime.contains("mp3")) {
            return "mp3";
        }
        if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) {
            return "m4a";
        }
        if (mime.contains("opus")) {
            return "opus";
        }
        if (mime.contains("ogg")) {
            return "ogg";
        }
        if (mime.contains("amr")) {
            return "amr";
        }
        return "wav";
    }
}
