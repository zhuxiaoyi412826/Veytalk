package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.FileDTO;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FileStorageSpi;
import com.im.common.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 通话记录消息处理（{@link MsgType#CALL}）。
 *
 * <p>一次语音 / 视频通话结束后由客户端落一条本消息：{@code content} 存录制文件 ID（未录制时为空），
 * {@code extra} 带媒体类型、时长与是否录屏。它不是实时信令（信令走 WebSocket），而是这通电话的「结算凭证」，
 * 作用与语音留言类似——所以复用附件那套「按 fileId 回填元数据、不信任客户端上报值」的思路。
 *
 * <p>三道服务端收敛：
 * <ul>
 *   <li>录制文件必须归属发送者本人、且 {@code bizType} 必须是 {@code call_record}，
 *       否则可以拿任意别人的文件 ID 冒充成一段通话录音外发；</li>
 *   <li>媒体类型只认 audio / video，其余归一化为 audio；</li>
 *   <li>通话时长非负且不超过 {@code im.call.max-duration-seconds}，能取到录制文件真实时长时以文件为准。</li>
 * </ul>
 */
@Slf4j
@Component
public class CallContentHandler implements MessageContentHandler {

    /**
     * 录制文件的业务类型，须与 im-file 的 {@code FileBizType.CALL_RECORD.code} 一致。
     * 用字面量而不是直接引 im-file 的枚举：im-message 只依赖 im-common 的 {@link FileStorageSpi}，
     * 反向依赖 im-file 会破坏模块边界。
     */
    private static final String BIZ_CALL_RECORD = "call_record";

    private static final String MEDIA_VIDEO = "video";

    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;
    private final ImProperties imProperties;

    public CallContentHandler(ObjectProvider<FileStorageSpi> fileStorageSpiProvider,
                              ImProperties imProperties) {
        this.fileStorageSpiProvider = fileStorageSpiProvider;
        this.imProperties = imProperties;
    }

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.CALL);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();

        // 媒体类型与录屏标记归一：任何非 video 一律落 audio，screenRecorded 只保留布尔
        extra.setCallMediaType(MEDIA_VIDEO.equals(extra.getCallMediaType()) ? MEDIA_VIDEO : "audio");
        extra.setCallScreenRecorded(Boolean.TRUE.equals(extra.getCallScreenRecorded()));

        int maxDuration = imProperties.getCall().getMaxDurationSeconds();
        Long fileId = resolveFileId(cmd);
        if (fileId == null) {
            // 未录制的通话：只留时长与类型，content 清空
            extra.setFileId(null);
            extra.setDuration(clampDuration(extra.getDuration(), maxDuration));
            cmd.setContent(null);
            cmd.setExtra(extra);
            return;
        }

        FileStorageSpi fileSpi = fileStorageSpiProvider.getIfAvailable();
        if (fileSpi == null) {
            // 文件模块缺席：保留 fileId 让消息先落库，元数据待文件模块上线后由前端按需拉取
            log.warn("[通话记录] 文件服务未装配，跳过归属校验与元数据回填: fileId={}", fileId);
            extra.setFileId(fileId);
            extra.setDuration(clampDuration(extra.getDuration(), maxDuration));
            cmd.setContent(String.valueOf(fileId));
            cmd.setExtra(extra);
            return;
        }

        FileDTO file = fileSpi.getById(fileId);
        BusinessException.throwIf(file == null, ResultCode.FILE_NOT_FOUND);
        // 归属校验：通话记录只能引用发送者自己上传的录制文件
        BusinessException.throwIf(!cmd.getFromUserId().equals(file.getUploaderId()),
                ResultCode.FILE_DOWNLOAD_FORBIDDEN);
        // 业务类型闸门：防止拿别的通话之外的文件 ID（甚至他人文件）冒充成通话录音
        BusinessException.throwIf(!BIZ_CALL_RECORD.equals(file.getBizType()),
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        extra.setFileId(fileId);
        extra.setFileName(file.getOriginalName());
        extra.setFileSize(file.getSize());
        extra.setContentType(file.getContentType());
        extra.setExt(TextUtil.isBlank(file.getExt()) ? TextUtil.extension(file.getOriginalName()) : file.getExt());
        // 时长以录制文件的真实时长为准，取不到再回落到客户端上报值（两者都限幅）
        Integer fileDuration = file.getDuration();
        extra.setDuration(fileDuration != null && fileDuration > 0
                ? clampDuration(fileDuration, maxDuration)
                : clampDuration(extra.getDuration(), maxDuration));
        // 存受控地址：长期有效，且接收端下载时会再校验一次会话成员身份
        extra.setFileUrl(fileSpi.accessUrl(fileId));

        cmd.setContent(String.valueOf(fileId));
        cmd.setExtra(extra);
    }

    private int clampDuration(Integer seconds, int max) {
        if (seconds == null || seconds < 0) {
            return 0;
        }
        return Math.min(seconds, max);
    }

    /** 与附件同一口径：fileId 优先取 extra.fileId，其次把 content 当数字解析 */
    private Long resolveFileId(MessageSendCmd cmd) {
        if (cmd.getExtra() != null && cmd.getExtra().getFileId() != null) {
            return cmd.getExtra().getFileId();
        }
        if (TextUtil.isBlank(cmd.getContent())) {
            return null;
        }
        try {
            return Long.parseLong(cmd.getContent().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
