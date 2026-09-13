package com.im.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

/**
 * Jackson 3 全局定制。
 *
 * <p>Spring Boot 4 的定制入口是 {@link JsonMapperBuilderCustomizer}（不再是 Boot 3 的
 * {@code Jackson2ObjectMapperBuilderCustomizer}），容器内所有走自动配置的 mapper
 * ——HTTP 消息转换器、{@code JsonUtil}、以及被 {@code MybatisPlusConfig} 注入到
 * {@code Jackson3TypeHandler} 的那一个——都由它统一塑造，不存在「接口一套、数据库 JSON 列另一套」。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /**
     * 保护雪花 ID 不被 JavaScript 截断。
     *
     * <p>主键用的是 19 位雪花 ID，而 JavaScript 的 Number 是 IEEE-754 双精度，
     * 只能精确表示到 2^53-1（16 位）。前端 {@code JSON.parse} 一个
     * {@code 1948234567890123456} 实际得到 {@code 1948234567890123400}，
     * 再把这个值回传服务端就指向一条不存在的记录，表现成「所有按 ID 查的接口都 404」，
     * 而且日志里的 ID 是对的，极难归因。
     *
     * <p>具体转不转由 {@link SafeLongSerializer} 按值判断：超出安全整数范围才写成字符串。
     * 之所以不用现成的 {@code ToStringSerializer} 一刀切，是因为本项目的 {@code Long}
     * 字段里还混着消息序号（{@code seq}）与文件字节数，那些值转成字符串会直接弄坏
     * 游标分页的排序与体积展示，理由写在 {@link SafeLongSerializer} 的类注释里。
     *
     * <p>只注册 {@code Long.class}、刻意不注册 {@code Long.TYPE}：本项目所有标识符都声明为
     * 包装类型（可空、直接来自数据库），计数与时间戳一律是基本类型
     * （{@code PageResult.total}、{@code Result.timestamp}、{@code WsPacket.timestamp}）。
     * Jackson 3 不会因为注册了 {@code Long.class} 就把基本类型 {@code long} 一并接管，
     * 于是分页组件拿到的 {@code total} 仍然是数字，不必在前端做 {@code Number()} 转换。
     *
     * <p>反序列化方向不受影响：Jackson 对 {@code Long} 字段同时接受字符串与数字，
     * 前端把 ID 原样回传、或走 REST 查询参数传 ID，两条路都照旧工作。
     */
    @Bean
    public JsonMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("im-safe-long");
            module.addSerializer(Long.class, SafeLongSerializer.INSTANCE);
            builder.addModule(module);
        };
    }
}
