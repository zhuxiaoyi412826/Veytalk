package com.im.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 唯一启动类。全部业务模块由 im-bootstrap 聚合，打包成单个可执行 jar。
 *
 * <h2>为什么必须显式写 scanBasePackages 与 MapperScan</h2>
 *
 * <p>{@code @SpringBootApplication} 默认只扫描<strong>启动类所在包</strong>及其子包，也就是
 * {@code com.im.bootstrap}。而八个业务模块的包是 {@code com.im.user}、{@code com.im.message} 这样的
 * <em>兄弟</em>包，不在 {@code com.im.bootstrap} 之下——不加 {@code scanBasePackages = "com.im"}，
 * 应用能正常启动、端口也通，但所有 Controller 与 Service 一个都不会被注册，
 * 表现成「全部接口 404」，且日志里没有任何报错。
 *
 * <p>同理，MyBatis 的 {@code @Mapper} 自动扫描也是基于「自动配置包」（同样只有 {@code com.im.bootstrap}），
 * 所以 14 个 Mapper 必须靠 {@code @MapperScan} 显式捞进来。这里用 {@code com.im.**.mapper}
 * 通配所有模块，新增模块只要遵循既有的分包约定就自动生效，不必回来改这一行。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.im")
@MapperScan("com.im.**.mapper")
@EnableScheduling
@EnableTransactionManagement
public class ImApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ImApplication.class, args);
        logStartupSummary(context.getEnvironment());
    }

    /**
     * 打印启动摘要，把「该访问哪里」直接写在日志里。
     *
     * <p>端口、上下文路径、生效 profile 都可能是环境变量改过的，凭记忆去猜地址是排障时最常见的
     * 时间浪费。文件存储实现也一并打出来：local 与 minio 的行为差异（是否支持预签名直链）
     * 只有启动时看一眼才能确认。
     */
    private static void logStartupSummary(Environment env) {
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String baseUrl = "http://localhost:" + port + contextPath;
        log.info("""

                ----------------------------------------------------------
                  IM 即时通讯系统启动完成
                  生效 Profile : {}
                  接口文档     : {}/doc.html
                  WebSocket    : ws://localhost:{}/ws
                  文件存储     : {}
                ----------------------------------------------------------""",
                String.join(",", env.getActiveProfiles()),
                baseUrl, port,
                env.getProperty("im.file.storage", "local"));
    }
}
