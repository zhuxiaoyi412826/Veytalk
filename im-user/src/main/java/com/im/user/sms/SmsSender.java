package com.im.user.sms;

/**
 * 短信下发通道。
 *
 * <p>抽这一层的原因和 {@code im-file} 的 {@code FileStorage} 一样：验证码的生成、频率限制、
 * Redis 存储与校验都在 {@code CaptchaServiceImpl} 里，与「短信怎么发出去」是两件事。
 * 真实短信服务商（阿里云 / 腾讯云 / 华为云）各有自己的 SDK、签名与模板 ID 规则，
 * 把这些细节关在实现类里，业务代码只依赖本接口，换服务商不需要动验证码逻辑。
 *
 * <p>实现类通过 {@code @ConditionalOnProperty(im.sms.provider)} 二选一装配，
 * 未配置时默认走 {@link MockSmsSender}。接入真实服务商时新增一个实现类，
 * 标 {@code @ConditionalOnProperty(name = "im.sms.provider", havingValue = "aliyun")} 即可，
 * 无需修改任何调用方。
 *
 * <p>★ {@code im.sms.provider} 写了一个还没有对应实现类的服务商（目前只有 mock 这一个实现，
 * 直接配 aliyun / tencent 就是这种情况）会得到零个 bean：调用方把它当「可选通道」看待，
 * 发码时抛 {@code SMS_NOT_CONFIGURED} 并留一条 WARN，应用照常启动。
 * 短信通道配置错误不该让登录、消息、群聊等与它无关的功能一起挂掉。
 *
 * <p>约定：实现类只负责「把这条短信发出去」，失败时抛异常；
 * 频率限制、验证码写入 Redis 都由调用方在发送前后完成，实现类不要碰 Redis。
 */
public interface SmsSender {

    /**
     * 下发一条验证码短信。
     *
     * @param phone      接收手机号，明文；日志中必须自行脱敏
     * @param sceneName  场景中文名，如「登录」「找回密码」，供模板文案与日志使用
     * @param code       6 位验证码
     * @param ttlSeconds 验证码有效期（秒），用于拼「X 分钟内有效」文案
     */
    void sendCode(String phone, String sceneName, String code, long ttlSeconds);

    /**
     * 通道标识，写日志与排障用，如 {@code mock} / {@code aliyun}。
     */
    String provider();
}
