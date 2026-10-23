package com.im.live.service;

import com.im.live.config.LiveProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 流媒体地址的拼装与签名（直播与回放共用同一套算法）。
 *
 * <p>为什么单拎出来：直播播放地址与回放地址是<b>同一个 playBaseUrl 下的两个不同路径</b>，
 * 签名口径（{@code md5(path + expire + secret)} → base64url 无填充）必须逐字一致，
 * 抄两份迟早会漂。而 Nginx 的 {@code secure_link_md5} 只配一处，两边任何一处算错都是全线 403。
 *
 * <p>签名只对 URL 的 <b>path</b> 计算，且 path 必须是 Nginx 看到的 {@code $uri}（含 location
 * 前缀）：所以先拼出完整地址、再从里面抠出 path 去算摘要，而不是拿整个 URL 去算——
 * 多算进 scheme/host 会让签名永远对不上。{@code playBaseUrl} 写成相对路径（本机自测的
 * {@code /hls}）时 {@link URI#create} 得到的 path 就是它本身，同样成立。
 */
@Component
@RequiredArgsConstructor
public class LiveUrlSigner {

    /** 直播清单文件名：{@code {roomId}/{streamKey}/index.m3u8} */
    private static final String PLAYLIST = "index.m3u8";
    /** 回放清单的路径后缀：不带 streamKey，是「这一场」的持久地址 */
    private static final String REPLAY_SUFFIX = "/replay/" + PLAYLIST;

    private final LiveProperties properties;

    /** 本场直播的播放地址（带签名），{@code {playBaseUrl}/{roomId}/{streamKey}/index.m3u8?expire=&sign=} */
    public String playUrl(Long roomId, String streamKey) {
        return sign(roomId + "/" + streamKey + "/" + PLAYLIST);
    }

    /** 本场的回放地址（带签名），{@code {playBaseUrl}/{roomId}/replay/index.m3u8?expire=&sign=} */
    public String replayUrl(Long roomId) {
        return sign(roomId + REPLAY_SUFFIX);
    }

    /**
     * 给「相对 playBaseUrl 的路径」签一个时效地址。
     *
     * <p>摘要以 <b>base64url（无填充）</b>编码，与 {@code ngx_http_secure_link_module} 一致：
     * 该模块把 {@code secure_link_md5} 指定的串做 md5 后 base64url 编码再与 URL 上的 sign 比对，
     * 并在 expire 早于当前时间时判失效。用十六进制会永远对不上。
     */
    public String sign(String relativePath) {
        String full = absolute(properties.getPlayBaseUrl(), relativePath);
        String path = pathOf(full);
        long expire = System.currentTimeMillis() / 1000L + properties.getSignTtlSeconds();
        String raw = path + expire + properties.getSignSecret();
        byte[] digest = DigestUtils.md5Digest(raw.getBytes(StandardCharsets.UTF_8));
        String sign = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return full + "?expire=" + expire + "&sign=" + sign;
    }

    /** 拼接基址与相对路径，吃掉基址尾部与相对头部的重复斜杠。推流地址（pushBaseUrl 下）也用它 */
    public String absolute(String base, String relative) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = relative.startsWith("/") ? relative.substring(1) : relative;
        return left + "/" + right;
    }

    /** 从绝对 URL 中取出 path（含前导斜杠），解析失败时退回「去掉 scheme://host」的粗略切法 */
    private String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            int schemeEnd = url.indexOf("://");
            int pathStart = schemeEnd < 0 ? url.indexOf('/') : url.indexOf('/', schemeEnd + 3);
            return pathStart < 0 ? "" : url.substring(pathStart);
        }
    }
}
