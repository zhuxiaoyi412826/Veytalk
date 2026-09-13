package com.im.common.util;

import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具，复用 Spring Boot 4 自动配置的 Jackson 3 {@code ObjectMapper}，
 * 从而继承全局的 JavaTime、命名策略等定制，避免自建 mapper 与 Web 层行为不一致。
 */
@Slf4j
@Component
public class JsonUtil {

    private final ObjectMapper objectMapper;

    public JsonUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    /**
     * 序列化为 JSON 字符串，失败返回 {@code null}。
     */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 反序列化为指定类型，失败抛出业务异常。
     */
    public <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("JSON 反序列化失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.BAD_REQUEST, "报文格式错误");
        }
    }

    /**
     * 反序列化为泛型类型。
     */
    public <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            log.warn("JSON 反序列化失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.BAD_REQUEST, "报文格式错误");
        }
    }

    /**
     * 静默反序列化，失败返回 {@code null}，用于解析可选的扩展字段。
     */
    public <T> T fromJsonQuietly(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析为 Map，失败返回空 Map。
     */
    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 解析为 List，失败返回空 List。
     */
    public <T> List<T> toList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 对象类型转换，例如把 Map 转为具体 DTO。
     */
    public <T> T convert(Object source, Class<T> clazz) {
        if (source == null) {
            return null;
        }
        if (clazz.isInstance(source)) {
            return clazz.cast(source);
        }
        try {
            return objectMapper.convertValue(source, clazz);
        } catch (Exception e) {
            log.warn("对象转换失败: {}", e.getMessage());
            return null;
        }
    }
}
