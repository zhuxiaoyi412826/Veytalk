package com.im.live.service;

import com.im.common.config.ImProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 作品板块（图片 / 音乐 / 小说）的受控访问地址拼接。
 *
 * <p>与 {@code LiveVideoServiceImpl#accessUrl} 同一算法（{@code im.file.access-url-prefix + "/" +
 * fileId}），只是抽出来给三个板块共用：抄三份的风险不在于今天算错，而在于日后前缀口径变了
 * （比如改成带签名的下载地址）只改到其中两份，剩那两个板块的卡片静默裂图。
 *
 * <p>地址一律<b>服务端现拼、不采信前端传来的 URL</b>：前端只能决定「我引用哪个 fileId」，
 * 拼出来的永远是本站受控下载地址。否则一条外部直链或别人文件的地址就能被写进信息流。
 */
@Component
@RequiredArgsConstructor
public class WorkAccessUrls {

    private final ImProperties imProperties;

    /** 单个文件的受控访问地址 */
    public String of(Long fileId) {
        return imProperties.getFile().getAccessUrlPrefix() + "/" + fileId;
    }

    /**
     * 按顺序拼成逗号并列串（图片板块的 {@code image_urls} 列）。
     *
     * <p>元素是雪花 ID 与受控地址，两者都不含逗号，所以并列串能无歧义反解——这也是
     * {@code LiveImage} 上不建子表的前提。传空集合得到空串而不是 {@code null}，列是 NOT NULL。
     */
    public String join(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return "";
        }
        List<String> urls = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            urls.add(of(fileId));
        }
        return String.join(",", urls);
    }
}
