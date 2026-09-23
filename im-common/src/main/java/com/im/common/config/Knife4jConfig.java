package com.im.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 文档配置。
 *
 * <p>Spring Boot 4 使用 knife4j-next 分支（{@code com.baizhukui}），其传递的 springdoc-openapi 3.x
 * 仍复用 {@code io.swagger.v3.oas.models} 与 {@code org.springdoc.core.models.GroupedOpenApi}。
 *
 * <p>文档地址：{@code http://localhost:8080/doc.html}。
 * 鉴权方式：点击右上角「Authorize」填入登录接口返回的 token（无需 Bearer 前缀，
 * Sa-Token 以 {@code is-read-header=true} + {@code token-name=satoken} 读取）。
 */
@Configuration(proxyBeanMethods = false)
public class Knife4jConfig {

    /** 安全方案名称，与各分组的全局请求头保持一致 */
    private static final String SECURITY_SCHEME_NAME = "satoken";

    @Bean
    public OpenAPI imOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("IM 即时通讯系统")
                        .description("""
                                基于 Spring Boot 4 + JDK 21 的多模块即时通讯后端。

                                模块划分：user 用户中心 / friend 好友关系 / conversation 会话管理 /
                                message 消息核心 / group 群组管理 / file 文件存储 / websocket 实时推送。

                                鉴权说明：先调 `/api/auth/login` 获取 token，再点击右上角 Authorize 填入，
                                后续请求会自动携带 `satoken` 请求头。
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("IM Team"))
                        .license(new License().name("Apache 2.0")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(SECURITY_SCHEME_NAME)
                                .description("登录接口返回的 token 值，直接粘贴即可")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    @Bean
    public GroupedOpenApi userApi() {
        return build("01-用户中心", "/api/auth/**", "/api/captcha/**", "/api/user/**");
    }

    @Bean
    public GroupedOpenApi friendApi() {
        return build("02-好友关系", "/api/friend/**");
    }

    @Bean
    public GroupedOpenApi conversationApi() {
        return build("03-会话管理", "/api/conversation/**");
    }

    @Bean
    public GroupedOpenApi messageApi() {
        return build("04-消息核心", "/api/message/**");
    }

    @Bean
    public GroupedOpenApi groupApi() {
        return build("05-群组管理", "/api/group/**");
    }

    @Bean
    public GroupedOpenApi fileApi() {
        return build("06-文件存储", "/api/file/**");
    }

    @Bean
    public GroupedOpenApi websocketApi() {
        return build("07-实时推送", "/api/ws/**");
    }

    @Bean
    public GroupedOpenApi aiApi() {
        return build("08-后端 Java 全栈面试", "/api/ai/**");
    }

    /**
     * 按路径前缀分组，而不是按包名。
     *
     * <p>按包名分组的版本实测过：七个分组各自都返回了全部 60 个接口，过滤根本没生效，
     * 而当时 {@code application.yml} 里还同时设着全局 {@code springdoc.packages-to-scan=com.im}。
     * 改成 {@code pathsToMatch} 之后每组的接口数恢复正确
     * （13/13/8/9/11/5/1，合计 60，互不重叠也无遗漏），所以保留路径方案。
     *
     * <p>路径分组本身也更稳：它不依赖 Controller 的物理包位置，
     * 新增接口只要前缀对就自动归位，不会因为类放错包而在文档里静默消失。
     *
     * <p>全局的 {@code springdoc.packages-to-scan} 仍然保留，作用是把扫描范围收敛在 com.im，
     * 避免第三方 starter 里的 Controller 混进文档；分组过滤则由这里负责。
     */
    private GroupedOpenApi build(String group, String... pathsToMatch) {
        return GroupedOpenApi.builder()
                .group(group)
                .pathsToMatch(pathsToMatch)
                .build();
    }
}
