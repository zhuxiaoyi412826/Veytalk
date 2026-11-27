package com.im.common.util;

import com.im.common.enums.DeviceType;

/**
 * 设备位（slot）的解析与拼装。
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>Sa-Token 从 1.41 起把设备维度拆成了 {@code deviceType}（浏览器 / 桌面端 / 安卓 …）
 * 和 {@code deviceId}（同一类型下的第几台）。本项目在这之前只有前者，所以
 * {@code deviceId} 这个请求字段历史上装的是<b>类型码</b>，导致「同端互斥」这件事根本没法表达：
 * 同一个 Chrome 开三个标签页，三者的值都是 {@code web}，一旦按它顶号就会把用户已经打开的
 * 页面互相挤下线——这正是当年 {@code kickSameDevice} 被删掉的原因。
 *
 * <p>本类把两者压成一个字符串 {@code 类型:实例} 作为<b>设备位</b>，让「WS 注册表键、
 * Redis 在线键的 field、im_user_device 的行、踢人接口的入参」四处用同一个值，
 * 而 Sa-Token 那边再拆回两个字段。四处不同源的话，就会出现「会话踢中了、socket 还活着」
 * 这种最难查的一半失效（{@code WsTicketService} 的注释早就警告过这点）。
 *
 * <h2>向后兼容是硬要求</h2>
 *
 * <p>已发布的 APK 与小程序只会传裸类型码（{@code android}），后端不能要求它们同时升级。
 * 因此 {@link #normalize} 对不含分隔符的值原样按类型码处理，得到的设备位与今天完全一致；
 * 而 {@link #hasInstance} 为 false 的设备位<b>一律不参与顶号</b>——
 * 宁可让老客户端继续享受「同端全部并存」，也不能让它退化成「两台手机互相踢下线」。
 * 顶号只对升级到带实例 ID 的客户端生效，这也是这个功能可以安全上线的前提。
 */
public final class DeviceIds {

    /**
     * 类型与实例的分隔符。选 {@code :} 而不是 {@code -}：UUID 本身就带 {@code -}，
     * 用 {@code -} 做分隔符时 {@code android-a-b} 无法判定边界；而五个类型码里都不含 {@code :}，
     * Redis 键里出现 {@code :} 也不影响（它只作为 Hash field 的整个值存在，不参与键的切分）。
     */
    public static final char SEP = ':';

    /** 实例 ID 的列宽与长度上限，超出的部分截断（客户端正常是 36 位 UUID，这里只防恶意超长） */
    public static final int MAX_INSTANCE_LENGTH = 64;

    /** 设备名的列宽，客户端上送的 {@code X-Device-Name} 按此截断 */
    public static final int MAX_NAME_LENGTH = 50;

    private DeviceIds() {
    }

    /**
     * 清洗客户端上送的设备位。
     *
     * <ul>
     *   <li>{@code null} / 空白 → {@code web}，与改造前 {@code SecurityUtil.getDevice()} 的兜底一致；</li>
     *   <li>含 {@code :} → 校验类型段（未知类型回落 {@code web}）、实例段只留字母数字与
     *       {@code -} {@code _}（挡掉换行与 {@code *} 之类会污染 Redis field 的字符），
     *       清洗后实例段为空则退化为裸类型码；</li>
     *   <li>不含 {@code :} → 走 {@link DeviceType#codeOf}，未知值当 {@code web}（老客户端的行为）。</li>
     * </ul>
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DeviceType.WEB.getCode();
        }
        String value = raw.trim();
        int sep = value.indexOf(SEP);
        if (sep < 0) {
            return DeviceType.codeOf(value);
        }
        String type = DeviceType.codeOf(value.substring(0, sep));
        String instance = sanitizeInstance(value.substring(sep + 1));
        return instance.isEmpty() ? type : type + SEP + instance;
    }

    /**
     * 拼装设备位。实例 ID 为空时只返回类型码，与老客户端上送的形状一致。
     */
    public static String slot(String deviceType, String instance) {
        String type = DeviceType.codeOf(deviceType);
        String inst = sanitizeInstance(instance);
        return inst.isEmpty() ? type : type + SEP + inst;
    }

    /**
     * 取设备位里的类型码。裸类型码原样返回，未知值回落 {@code web}，因此本方法不会返回 null。
     *
     * <p>每端允许几台、后台展示「哪个端」、{@code im:online} 的历史读数都靠它，
     * 所以必须能在只拿到设备位时就把类型问出来，不能要求调用方另外带上类型字段。
     */
    public static String typeOf(String slot) {
        if (slot == null || slot.isBlank()) {
            return DeviceType.WEB.getCode();
        }
        String value = slot.trim();
        int sep = value.indexOf(SEP);
        return DeviceType.codeOf(sep < 0 ? value : value.substring(0, sep));
    }

    /**
     * 取设备位里的实例 ID；裸类型码（老客户端）返回 {@code null}。
     */
    public static String instanceOf(String slot) {
        if (slot == null) {
            return null;
        }
        int sep = slot.indexOf(SEP);
        if (sep < 0 || sep == slot.length() - 1) {
            return null;
        }
        return slot.substring(sep + 1);
    }

    /**
     * 这个设备位能不能定位到「单独一台机器」。
     *
     * <p>顶号、撤销单台设备这类动作的前置判定。为 false 时只能整体并存，
     * 因为此时后端根本分不清对面是几台设备。
     */
    public static boolean hasInstance(String slot) {
        return instanceOf(slot) != null;
    }

    /**
     * 是否老客户端上送的裸类型码（不含实例 ID）。
     */
    public static boolean isLegacy(String slot) {
        return !hasInstance(slot);
    }

    /**
     * 清洗设备名称：去控制字符、压掉多余空白、按列宽截断。
     *
     * <p>这个值会原样出现在被踢端的提示文案里（「你的账号在 <b>xxx</b> 登录」），
     * 是一个完全由客户端决定的字符串，所以既要防注入到日志（换行伪造日志行），
     * 也要防一句超长文案把弹窗撑破。
     */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        cleaned = cleaned.replaceAll("\\s{2,}", " ");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    private static String sanitizeInstance(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_INSTANCE_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < MAX_INSTANCE_LENGTH; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
