package com.im.file.service;

import cn.hutool.core.convert.Convert;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 文件访问票据的签发与校验。
 *
 * <p>存在这个类的唯一理由是浏览器：{@code <img src>}、{@code <audio src>}、{@code <a download>}
 * 发起的请求都带不上 {@code satoken} 请求头，只有 Cookie 会自动跟随，而本项目刻意关掉了 Cookie 读取
 * （{@code sa-token.is-read-cookie=false}）以避免 CSRF。于是改用 URL 上的短时 JWT 票据，
 * 它同时解决了「不暴露长期登录凭证」与「图片能直接渲染」两个问题。
 *
 * <p>票据与文件 ID 绑定，一张票据只能取一个文件；同时带上用途标识，
 * 防止 WebSocket 握手票据（同样由 {@link JwtUtil} 签发）被挪用来下载文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileTicketService {

    /** 票据用途标识 */
    public static final String PURPOSE = "file-access";

    private static final String CLAIM_FILE_ID = "fileId";

    private final JwtUtil jwtUtil;
    private final ImProperties imProperties;

    /**
     * 按配置的默认有效期签发票据。
     */
    public String issue(Long fileId, Long userId) {
        return issue(fileId, userId, imProperties.getJwt().getFileTicketTtlSeconds());
    }

    /**
     * 按指定有效期签发票据。
     */
    public String issue(Long fileId, Long userId, long ttlSeconds) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put(JwtUtil.CLAIM_USER_ID, userId);
        claims.put(JwtUtil.CLAIM_PURPOSE, PURPOSE);
        claims.put(CLAIM_FILE_ID, fileId);
        return jwtUtil.createToken(claims, Duration.ofSeconds(Math.max(ttlSeconds, 1L)));
    }

    /**
     * 校验票据并返回其中的用户 ID。
     *
     * <p>用 {@code parseQuietly} 而不是 {@code parse}：后者失败时抛的是
     * {@code WS_TICKET_INVALID}，一个文件下载请求回一句「WebSocket 票据无效」会把排查带偏。
     *
     * @throws BusinessException 票据缺失、无效、过期、用途不符时抛 {@code UNAUTHORIZED}；
     *                           票据有效但与目标文件不匹配时抛 {@code FILE_DOWNLOAD_FORBIDDEN}
     */
    public Long verify(String ticket, Long fileId) {
        Map<String, Object> claims = jwtUtil.parseQuietly(ticket);
        if (claims == null) {
            log.debug("[文件票据] 校验失败，票据无效或已过期: fileId={}", fileId);
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        BusinessException.throwUnless(PURPOSE.equals(claims.get(JwtUtil.CLAIM_PURPOSE)), ResultCode.UNAUTHORIZED);
        Long ticketFileId = Convert.toLong(claims.get(CLAIM_FILE_ID));
        BusinessException.throwUnless(Objects.equals(ticketFileId, fileId), ResultCode.FILE_DOWNLOAD_FORBIDDEN);
        Long userId = Convert.toLong(claims.get(JwtUtil.CLAIM_USER_ID));
        BusinessException.throwIf(userId == null, ResultCode.UNAUTHORIZED);
        return userId;
    }
}
