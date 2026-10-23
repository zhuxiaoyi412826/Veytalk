package com.im.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理后台配置，挂在 {@code im.admin} 前缀下。
 *
 * <p>为什么放在本模块而不是 im-common 的 {@code ImProperties}：这些旋钮<b>只有 im-admin 自己读</b>。
 * {@code ImProperties.linkmic} 之所以不得不放公共层，是因为连麦信令服务在 im-websocket、
 * 配置却要 im-live 写，两层都要看得见；举报配额与看板缓存没有这种跨模块读者。
 * 放本模块的代价是：配置项散在两个前缀下，看 {@code application.yml} 时要认得出 {@code im.admin} 归谁。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im.admin")
public class AdminProperties {

    /**
     * 举报入口总开关。
     *
     * <p>关掉的是<b>用户提交举报</b>（{@code POST /api/report} 直接拒绝），后台已有工单仍可查看与处置——
     * 已经报上来的事不能因为运维关个开关就当没发生，那是把用户的求助当噪声处理。
     * 前端举报入口按 {@code GET /api/report/config} 返回的开关隐藏，不让用户点了才报错。
     */
    private boolean enabled = true;

    /**
     * 单个用户每日可提交的举报条数上限。
     *
     * <p>防的是「对着一个人连点二十次举报」把队列刷满，不是防正常用户。
     * 20 条足够一个真实用户一天里遇到的所有坏人，再多就是脚本。
     */
    private int dailyQuota = 20;

    /** 同一用户对同一对象的去重窗口（小时），窗口内重复提交报 {@code REPORT_DUPLICATED} */
    private int dedupHours = 24;

    /** 补充描述最大长度，与表列 {@code reason_text varchar(500)} 对齐；改大要一起改表 */
    private int reasonTextMax = 500;

    /**
     * 证据摘要最大长度。
     *
     * <p>由各模块的 SPI 实现自己截断（它才知道正文在哪里），im-admin 在落库前再夹一道兜底：
     * 契约管不住实现，<b>两道比一道便宜</b>，多一次 substring 换的是「工单表不会被一段聊天记录撑爆」。
     */
    private int excerptMax = 200;

    /**
     * 看板聚合缓存秒数。
     *
     * <p>0 表示不缓存（每次刷新都真跑一遍 COUNT，本地库调试用）。
     * 线上别设太大：看板上的数字与队列实际情况差得越久，管理员越容易按旧数做处置决定；
     * 无论取值多少，页面都会显示「数据截至 HH:mm:ss」，滞后是明写出来的而不是猜的。
     */
    private int dashboardCacheSeconds = 60;

    /**
     * 后台处置动作是否需要填写说明。
     *
     * <p>默认 true：封号、撤回消息、中断直播都是伤用户的动作，不留一句理由的话，
     * 事后没人能回答「当时为什么动了他」。关掉这个开关只该出现在自动化脚本接入的场景。
     */
    private boolean handleRemarkRequired = true;

    /**
     * 处置人是否对举报人可见（后台二期的可选透明化旋钮）。
     *
     * <p>默认 <b>false</b>：沿用一期口径——{@code ReportVO} 不带处置人信息，管理员是谁对普通用户没意义，
     * 一旦进响应就可能被截图当成「平台内部人员名录」。打开后，举报人自己的工单列表（{@code /api/report/mine}）
     * 每行会带 {@code handlerName}，处理结论通知的文案也会署名处置人昵称，便于「有事知道找谁」。
     *
     * <p>这是一个静态 yml 开关而非配置页旋钮：二期没有做系统配置表，改这个值需要改 {@code application.yml}
     * 重启生效；之所以宁可麻烦一点，是因为它改的是「对外的隐私口径」，不该让谁都能在后台随手拨。
     */
    private boolean reportHandlerVisible = false;

    /* ==================== 监控告警与日志管理（三期） ==================== */

    /**
     * 慢接口阈值（毫秒）：{@code /api/**} 里耗时超过这个值的请求会被记进环形缓冲并写
     * {@code com.im.slowapi} 告警日志（落 {@code SLOW-API/slow-api.log}）。
     * <=0 表示关掉慢接口拦截（监控页仍看 actuator 指标，只是没有最近慢接口列表）。
     *
     * <p>与 {@code im.log.slow-sql-millis} 是两层不同的旋钮：那个量单条 SQL，这个量整个请求；
     * 一个接口慢可能是 N 条各自不超 SQL 阈值的查询叠出来的，两边都有才能定位。
     */
    private long slowRequestMillis = 1000;

    /** 慢接口环形缓冲条数：只留最近这些条给监控页，更早的靠日志文件回溯 */
    private int slowRequestHistory = 200;

    /**
     * 日志管理页单次 tail 的行数上限。文件最大 50MB，整读会先把后台自己拖死，
     * 所以只给尾读；需要全文时走服务器本地文件，后台不做下载端点（多一条把日志外带的路径）。
     */
    private int logTailMaxLines = 2000;
}
