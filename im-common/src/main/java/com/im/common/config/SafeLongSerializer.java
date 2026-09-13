package com.im.common.config;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * 只在超出 JavaScript 安全整数范围时，才把 {@code Long} 写成 JSON 字符串。
 *
 * <h2>为什么不无差别地全部转字符串</h2>
 *
 * <p>最省事的写法是注册 Jackson 自带的 {@code ToStringSerializer}，让所有 {@code Long} 都变字符串。
 * 但本项目的 {@code Long} 字段并不都是标识符：{@code MessageVO.seq}、{@code lastAckSeq}、
 * {@code maxSeq} 是会话内的消息序号，{@code FileVO.size} 是字节数。
 * 序号一旦变成字符串，前端的历史消息游标分页就退化成字典序比较
 * ——{@code "9" > "10"} 为真，翻到第 10 条之后顺序全乱，而且只在数据量长起来以后才暴露；
 * 字节数变成字符串，{@code size / 1024} 会先被隐式转成数字再算，看着对，
 * 但 {@code formatSize} 里任何一次 {@code typeof} 判断或字符串拼接都会出错。
 *
 * <h2>为什么按值判断是安全的</h2>
 *
 * <p>真正需要保护的只有一件事：19 位雪花 ID 超过 {@code Number.MAX_SAFE_INTEGER}（2^53-1，16 位），
 * 前端 {@code JSON.parse} 会把它截断成错值。序号与字节数永远远小于这个界限，本来就不需要转。
 * 所以「超出安全范围才转字符串」精确地只修复了会坏的那部分。
 *
 * <p>这个映射对同一个数值是确定的，因此服务端两处返回同一个 ID 时，前端拿到的类型必然一致，
 * {@code ===} 比较不会时灵时不灵。唯一要注意的是与前端自己构造的值比较：
 * 路由参数、{@code localStorage} 里的值天然是字符串，比较前统一 {@code String(id)} 归一即可。
 */
public class SafeLongSerializer extends ValueSerializer<Long> {

    /** JavaScript {@code Number.MAX_SAFE_INTEGER}，即 2^53 - 1 */
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private static final long MIN_SAFE_INTEGER = -MAX_SAFE_INTEGER;

    /** 无状态，单例复用即可 */
    public static final SafeLongSerializer INSTANCE = new SafeLongSerializer();

    @Override
    public Class<Long> handledType() {
        return Long.class;
    }

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        // 框架不会用 null 调进来（null 走 getNullValue），但序列化器里抛 NPE 极难定位，挡一下更划算
        if (value == null) {
            gen.writeNull();
            return;
        }
        long number = value;
        if (number > MAX_SAFE_INTEGER || number < MIN_SAFE_INTEGER) {
            gen.writeString(Long.toString(number));
        } else {
            gen.writeNumber(number);
        }
    }
}
