package com.im.common.enums;

import lombok.Getter;

/**
 * 举报理由码，写进 {@code im_report.reason_code}。
 *
 * <p><b>分类口径收在服务端</b>：前端只提交码，不允许自定分类。
 * 一旦让客户端传自由文本，工单列表就会出现「骂人」「辱骂」「人身攻击」三种写法，
 * 后台想按理由聚合（比如「色情类举报突增」）时全是对不上的字符串。
 * 展示文案由后台按码查这份字典，前端那份只是同名的副本。
 *
 * <p>刻意<b>不存「哪个理由适用于哪个对象类型」</b>：想过一遍，八个理由对
 * 用户 / 消息 / 直播间 / 群四类对象都成立（色情、辱骂、广告……在哪个载体上都可能是真的），
 * 加一张恒为真的适用范围表只会让人以为它在起作用。真正会「这个组合没法处理」的是
 * 处置动作，那份约束写在 {@link ReportDisposition} 上。
 */
@Getter
public enum ReportReason {

    /** 色情低俗 */
    PORN("porn", "色情低俗", false),
    /** 辱骂攻击 */
    ABUSE("abuse", "辱骂攻击", false),
    /** 涉恐涉暴等违法违规 */
    VIOLENCE("violence", "涉恐涉暴", false),
    /** 诈骗：要说清楚骗了什么，不然管理员无从查起 */
    FRAUD("fraud", "诈骗", true),
    /** 垃圾广告 */
    SPAM("spam", "垃圾广告", false),
    /** 侵犯权益（抄袭、盗号、冒用身份） */
    INFRINGEMENT("infringement", "侵犯权益", true),
    /** 危害未成年人 */
    MINOR("minor", "危害未成年人", true),
    /** 其他：兜底分类，必须写清楚到底发生了什么，否则工单没人能判 */
    OTHER("other", "其他", true);

    /** 落库的码串，同时也是展示字典的 key */
    private final String code;
    private final String desc;
    /** 是否必须附补充描述：为 true 时 {@code reason_text} 为空就拒绝提交 */
    private final boolean textRequired;

    ReportReason(String code, String desc, boolean textRequired) {
        this.code = code;
        this.desc = desc;
        this.textRequired = textRequired;
    }

    /**
     * 按码串反查枚举，忽略大小写与首尾空白。
     *
     * @return 未匹配时返回 {@code null}，调用方据此拒绝提交
     */
    public static ReportReason of(String code) {
        if (code != null) {
            for (ReportReason value : values()) {
                if (value.code.equalsIgnoreCase(code.trim())) {
                    return value;
                }
            }
        }
        return null;
    }
}
