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

    @Bean
    public GroupedOpenApi remoteApi() {
        return build("09-远程控制", "/api/remote/**");
    }

    @Bean
    public GroupedOpenApi liveApi() {
        return build("10-直播", "/api/live/**");
    }

    @Bean
    public GroupedOpenApi adminOverviewApi() {
        return build("11-后台-概览与日志", "/api/admin/dashboard/**", "/api/admin/monitor/**", "/api/admin/log/**");
    }

    @Bean
    public GroupedOpenApi adminAccountApi() {
        return build("12-后台-用户与权限", "/api/admin/user/**", "/api/admin/role/**");
    }

    @Bean
    public GroupedOpenApi adminContentApi() {
        return build("13-后台-内容运营", "/api/admin/article/**", "/api/admin/announcement/**",
                "/api/admin/video/**", "/api/admin/live/**", "/api/admin/report/**",
                "/api/admin/sensitive-word/**", "/api/admin/agreement/**");
    }

    @Bean
    public GroupedOpenApi adminBusinessApi() {
        return build("14-后台-业务与风控", "/api/admin/wallet/**", "/api/admin/message/**",
                "/api/admin/group/**", "/api/admin/ai/**", "/api/admin/interview/**",
                "/api/admin/remote/**", "/api/admin/config/**");
    }

    @Bean
    public GroupedOpenApi reportApi() {
        return build("15-举报", "/api/report/**");
    }

    @Bean
    public GroupedOpenApi walletApi() {
        return build("16-钱包", "/api/wallet/**");
    }

    @Bean
    public GroupedOpenApi articleApi() {
        return build("17-文章与公告", "/api/article/**", "/api/announcement/**");
    }

    @Bean
    public GroupedOpenApi callApi() {
        return build("18-通话与群机器人", "/api/call/**", "/api/robot/**");
    }

    /**
     * 法定文本与同意留痕（合规批）的用户侧。
     *
     * <p>单独一组而不是归进「01-用户中心」：这一组里有两条是<b>匿名</b>接口，
     * 在 Authorize 之前就该能调通；混进用户中心那一组，看文档的人会以为它们跟其余接口一样需要登录态，
     * 于是官网那个页脚链接到底该怎么接，就没人敢定。
     */
    @Bean
    public GroupedOpenApi agreementApi() {
        return build("19-协议与合规", "/api/agreement/**");
    }

    /**
     * 按路径前缀分组，而不是按包名。
     *
     * <p>按包名分组的版本实测过：当时七个分组各自都返回了全部 60 个接口，过滤根本没生效，
     * 而 {@code application.yml} 里还同时设着全局 {@code springdoc.packages-to-scan=com.im}。
     * 改成 {@code pathsToMatch} 之后每组的接口数恢复正确，所以保留路径方案。
     *
     * <p>目前是十九个分组，覆盖实测的 245 条路径（分组号与控制器 {@code @Tag} 的编号
     * 是两个独立序列，不必强求一致）。
     *
     * <p><strong>新增控制器时必须同步在这里补前缀。</strong>只写了 {@code @Tag}/{@code @Operation}
     * 而未登记分组前缀的接口，会完完整整地存在于全量 {@code /v3/api-docs} 里，却在
     * {@code doc.html} 的下拉里一个角落都不出现（{@code swagger-config} 只列已声明的分组，
     * 没有默认分组兜底）——当初 {@code /api/admin/**} 等 96 条路径就是这么静默丢掉的。
     * 自查口径：拿 {@code /v3/api-docs} 的路径数去减各分组 {@code pathsToMatch} 的命中数，差应为 0。
     *
     * <p>路径分组不依赖 Controller 的物理包位置：前缀登记过之后，新增接口放哪个包都能自动归位。
     * 代价就是上一段说的——新前缀得显式登记一次，漏一次就静默缺一片。
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
