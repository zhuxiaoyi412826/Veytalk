package com.im.user.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.im.common.api.ResultCode;
import com.im.common.enums.DeviceType;
import com.im.common.exception.BusinessException;
import com.im.common.util.QrCodeUtil;
import com.im.common.util.RedisUtil;
import com.im.user.dto.QrLoginState;
import com.im.user.dto.vo.QrLoginCreateVO;
import com.im.user.dto.vo.QrLoginStatusVO;
import com.im.user.service.QrLoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * {@link QrLoginService} 实现，扫码握手状态全存 Redis。
 *
 * <p>安全要点：
 * <ul>
 *   <li>二维码链接的 origin 经 {@link #normalizeBase} 清洗，只认 http/https 的 {@code scheme://host[:port]}，
 *       杜绝把任意串编进二维码造成的开放重定向；</li>
 *   <li>{@code scene} 即持有者的共享密钥，谁轮询到 status=2 谁拿到登录包，因此登录包一次性消费——
 *       {@link #poll} 返回 token 后立即删除 key，重放拿不到第二次；</li>
 *   <li>授权时的 userId 由 Controller 从当前 APP 登录态取，报文里没有 userId，无法替他人授权。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrLoginServiceImpl implements QrLoginService {

    /** 场景状态键前缀 */
    private static final String KEY_PREFIX = "im:qrlogin:";
    /** 二维码有效期（秒），超时未授权即自动失效（靠 Redis TTL 回收） */
    private static final Duration TTL = Duration.ofSeconds(120);

    private final RedisUtil redisUtil;

    @Override
    public QrLoginCreateVO create(String base) {
        String scene = UUID.randomUUID().toString().replace("-", "");
        QrLoginState state = new QrLoginState();
        state.setStatus(QrLoginStatusVO.STATUS_WAITING);
        state.setCreateTime(System.currentTimeMillis());
        redisUtil.setObject(key(scene), state, TTL);

        String root = normalizeBase(base);
        String link = root + "/transfer?scene=" + scene;
        String qrcode = QrCodeUtil.toPngBase64(link, QrCodeUtil.DEFAULT_SIZE);
        return QrLoginCreateVO.builder()
                .scene(scene)
                .qrcode(qrcode)
                .expiresIn(TTL.getSeconds())
                .build();
    }

    @Override
    public void markScanned(String scene) {
        QrLoginState state = requireState(scene);
        // 已授权 / 已过确认态都不回退，只在「待扫码」时推进一次
        if (state.getStatus() == QrLoginStatusVO.STATUS_WAITING) {
            state.setStatus(QrLoginStatusVO.STATUS_SCANNED);
            savePreservingTtl(scene, state);
        }
    }

    @Override
    public void authorize(String scene, Long userId) {
        QrLoginState state = requireState(scene);
        BusinessException.throwIf(state.getStatus() == QrLoginStatusVO.STATUS_AUTHORIZED,
                ResultCode.BAD_REQUEST, "该二维码已授权，请刷新后重试");
        // 以当前 APP 用户身份为 web 设备新签一个 token（is-concurrent=true，不挤掉 APP 自己的登录态）
        StpUtil.login(userId, StpUtil.createSaLoginParameter().setDeviceType(DeviceType.WEB.getCode()));
        SaTokenInfo info = StpUtil.getTokenInfo();
        state.setStatus(QrLoginStatusVO.STATUS_AUTHORIZED);
        state.setUserId(userId);
        state.setToken(info.getTokenValue());
        state.setTokenName(info.getTokenName());
        state.setExpiresIn(info.getTokenTimeout());
        savePreservingTtl(scene, state);
        log.info("[扫码登录] scene={} 已授权给用户 {}", scene, userId);
    }

    @Override
    public QrLoginStatusVO poll(String scene) {
        QrLoginState state = redisUtil.getObject(key(scene), QrLoginState.class);
        if (state == null) {
            // key 不存在：要么从未创建，要么 TTL 到期被回收，统一按失效回给前端
            return QrLoginStatusVO.builder().status(QrLoginStatusVO.STATUS_EXPIRED).build();
        }
        if (state.getStatus() < QrLoginStatusVO.STATUS_AUTHORIZED) {
            return QrLoginStatusVO.builder().status(state.getStatus()).build();
        }
        // 已授权：下发一次性登录包并立即销毁 scene，防止被重复轮询重放
        QrLoginStatusVO vo = QrLoginStatusVO.builder()
                .status(QrLoginStatusVO.STATUS_AUTHORIZED)
                .token(state.getToken())
                .tokenName(state.getTokenName())
                .expiresIn(state.getExpiresIn())
                .build();
        redisUtil.delete(key(scene));
        return vo;
    }

    private QrLoginState requireState(String scene) {
        QrLoginState state = redisUtil.getObject(key(scene), QrLoginState.class);
        if (state == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "二维码已失效，请刷新后重新扫码");
        }
        return state;
    }

    /**
     * 回写状态时保留原始剩余有效期：用当前 TTL 续写，避免因反复读写把 120 秒窗口无限拉长。
     */
    private void savePreservingTtl(String scene, QrLoginState state) {
        long remainSeconds = redisUtil.getExpire(key(scene));
        Duration ttl = remainSeconds > 0 ? Duration.ofSeconds(remainSeconds) : TTL;
        redisUtil.setObject(key(scene), state, ttl);
    }

    private String key(String scene) {
        return KEY_PREFIX + scene;
    }

    /**
     * 清洗前端传来的站点根地址：只保留 {@code scheme://host[:port]}，剥掉尾部斜杠与任何路径 / 查询。
     *
     * <p>与群邀请同款：二维码链接由后端拼，不能把前端传进来的任意字符串直接拼接（防 open-redirect）；
     * 只认 http/https，其他协议或解析失败一律当空处理，退化为相对路径 {@code /transfer}。
     */
    private static String normalizeBase(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = new java.net.URI(base.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return "";
            }
            if (uri.getHost() == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(scheme.toLowerCase()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                sb.append(':').append(uri.getPort());
            }
            return sb.toString();
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }
}
