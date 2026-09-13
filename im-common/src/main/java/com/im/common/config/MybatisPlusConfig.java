package com.im.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 全局配置。
 *
 * <p>Spring Boot 4 必须使用 {@code mybatis-plus-spring-boot4-starter}，
 * 且分页插件自 3.5.9 起被拆分到 {@code mybatis-plus-jsqlparser}，两个依赖缺一不可。
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfig {

    /**
     * 插件注册顺序有讲究：分页 -> 乐观锁 -> 全表更新删除拦截。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /**
     * 把 Spring Boot 4 自动配置的 Jackson 3 {@code ObjectMapper} 注入 MyBatis-Plus 的 JSON TypeHandler。
     *
     * <p>{@link Jackson3TypeHandler} 默认使用自己 new 出来的静态 mapper，不带 JavaTime 模块与全局命名策略，
     * 一旦 JSON 列里出现时间类型就会序列化失败。这里复用容器内的 mapper，让数据库 JSON 列与 HTTP 响应体行为一致。
     */
    @Bean
    public InitializingBean mybatisJsonTypeHandlerInitializer(ObjectMapper objectMapper) {
        return () -> Jackson3TypeHandler.setObjectMapper(objectMapper);
    }

    /**
     * 创建时间与更新时间自动填充。
     */
    @Component
    public static class ImMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
            strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
