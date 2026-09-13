package com.im.message.handler;

import com.im.common.api.ResultCode;
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
 * 附件类消息处理（图片 / 文件 / 语音）。
 *
 * <p>三种类型的差异只在展示层，落库结构完全一致：{@code content} 存文件 ID，
 * 元数据统一放 {@code extra}，因此共用一个处理器。
 *
 * <p>元数据一律以文件服务的记录为准回填，不信任客户端上报的值——
 * 客户端可以把一张图片的 fileSize 报成 1KB，也可以把别人的文件 ID 填进来，
 * 前者污染展示，后者是越权，都必须由服务端按 fileId 重新取真值。
 */
@Slf4j
@Component
public class AttachmentContentHandler implements MessageContentHandler {

    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;

    public AttachmentContentHandler(ObjectProvider<FileStorageSpi> fileStorageSpiProvider) {
        this.fileStorageSpiProvider = fileStorageSpiProvider;
    }

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.IMAGE, MsgType.FILE, MsgType.VOICE);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        Long fileId = resolveFileId(cmd);
        BusinessException.throwIf(fileId == null, ResultCode.MESSAGE_CONTENT_ILLEGAL);

        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();
        extra.setFileId(fileId);

        FileStorageSpi fileSpi = fileStorageSpiProvider.getIfAvailable();
        if (fileSpi == null) {
            // im-file 未装配时只保证消息本身能落库，元数据留待文件模块上线后由前端按需拉取
            log.warn("[附件消息] 文件服务未装配，跳过归属校验与元数据回填: fileId={}", fileId);
            cmd.setContent(String.valueOf(fileId));
            cmd.setExtra(extra);
            return;
        }

        FileDTO file = fileSpi.getById(fileId);
        BusinessException.throwIf(file == null, ResultCode.FILE_NOT_FOUND);
        // 归属校验：只能引用自己上传的文件，否则可以拿别人的 fileId 把私密文件广播出去
        BusinessException.throwIf(!cmd.getFromUserId().equals(file.getUploaderId()),
                ResultCode.FILE_DOWNLOAD_FORBIDDEN);

        extra.setFileName(file.getOriginalName());
        extra.setFileSize(file.getSize());
        extra.setContentType(file.getContentType());
        extra.setExt(TextUtil.isBlank(file.getExt()) ? TextUtil.extension(file.getOriginalName()) : file.getExt());
        extra.setDuration(file.getDuration());
        // 存后端受控地址而不是预签名直链：受控地址长期有效，且访问时会再校验一次会话成员身份
        extra.setFileUrl(fileSpi.accessUrl(fileId));

        cmd.setContent(String.valueOf(fileId));
        cmd.setExtra(extra);
    }

    /**
     * 文件 ID 优先取 {@code extra.fileId}，其次把 {@code content} 当数字解析，
     * 兼容「先上传拿到 fileId 再发消息」与「直接把 fileId 放 content」两种客户端写法。
     */
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
