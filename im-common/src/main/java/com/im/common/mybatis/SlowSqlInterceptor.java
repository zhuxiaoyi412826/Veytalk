package com.im.common.mybatis;

import com.im.common.constant.ImConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 慢 SQL 拦截器：把执行耗时超过阈值的语句单独打到 {@code com.im.slowsql} 这个 logger 上。
 *
 * <p>日志落盘位置由 im-common 的 logback-spring.xml 决定（{@code ${im.log.home}/SQL/}），
 * 那个 logger 设了 {@code additivity="false"}，所以一条慢 SQL 只进 SQL 目录，
 * 不会再重复出现在 DEBUG.log / info / warn 里。
 *
 * <p>为什么不用现成的两条路：
 * <ul>
 *   <li>{@code mybatis-plus.configuration.log-impl=StdOutImpl} 直接写 System.out，绕过 logback，
 *       既没有时间戳也没有 traceId，而且它打的是<b>所有</b> SQL，不止慢的；</li>
 *   <li>把 {@code com.im} 整包抬到 DEBUG（开发期确实这么开着，见 application-dev.yml）同样是全量打，
 *       生产不能开——那会把消息正文与手机号一起落盘。</li>
 * </ul>
 * 本拦截器只关心超阈值的语句，所以生产可以常开。
 *
 * <p>计时点在 {@link Executor} 层，包含结果集映射与二级缓存查询，也就是业务视角感受到的耗时，
 * 比只统计 JDBC execute 更接近「这条 SQL 慢不慢」的答案。代价是它不等于纯数据库时间：
 * 一次取一万行、映射本身花 300ms 也会被判慢，此时要看返回行数而不是急着加索引。
 *
 * <p>阈值 {@code <= 0} 表示整体关掉——比再加一个 boolean 开关少一项配置，语义也不含糊。
 */
@Slf4j
@Intercepts({
        // query 只拦 4 参的那个：Executor 链上是 CachingExecutor 被插件代理，
        // 它内部再调被包装对象的 6 参 query 时已经不再经过代理，写上去也拦不到，反而误导读者
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    /** 与 logback-spring.xml 里那个 additivity="false" 的 logger 名一一对应，改一处必须改两处 */
    private static final Logger SLOW_SQL_LOG = LoggerFactory.getLogger("com.im.slowsql");

    /** 拼好参数后的整条 SQL 上限，超出截断：IN 一个万级列表就能把日志文件冲得没法看 */
    private static final int MAX_SQL_LENGTH = 4000;

    /** 单个参数值的上限，聊天正文一类的大字段在这里收口 */
    private static final int MAX_VALUE_LENGTH = 200;

    private static final String MASKED_VALUE = "***";

    private final long slowSqlMillis;

    public SlowSqlInterceptor(long slowSqlMillis) {
        this.slowSqlMillis = slowSqlMillis;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (slowSqlMillis <= 0 || !SLOW_SQL_LOG.isWarnEnabled()) {
            return invocation.proceed();
        }
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            if (costMs >= slowSqlMillis) {
                report(invocation, costMs);
            }
        }
    }

    /**
     * 输出慢 SQL。整段包在 try 里：日志是旁路能力，任何异常都不许影响这次查询的结果。
     */
    private void report(Invocation invocation, long costMs) {
        try {
            Object[] args = invocation.getArgs();
            MappedStatement ms = (MappedStatement) args[0];
            Object parameter = args.length > 1 ? args[1] : null;
            SLOW_SQL_LOG.warn("慢SQL cost={}ms threshold={}ms op={} mapper={} sql={}",
                    costMs, slowSqlMillis, invocation.getMethod().getName(), ms.getId(), buildSql(ms, parameter));
        } catch (Exception e) {
            log.warn("慢 SQL 日志输出失败，已忽略：{}", e.toString());
        }
    }

    /**
     * 取出这条语句真正执行的 SQL，并把 {@code ?} 换成实际参数值。
     *
     * <p>之所以费力内联而不是直接打 {@code BoundSql.getSql()}：带着 {@code ?} 的语句拿去 EXPLAIN
     * 跑不了，而「把这条 SQL 贴到客户端复现」正是这份日志的唯一用途。打印用的是 MyBatis 已经算好的
     * 参数映射，不是重新解析 SQL，所以不会与实际执行的语句走偏。
     *
     * <p>顺带把 SQL 压成一行（引号外的换行与缩进折成单个空格），方便 grep 与 tail。
     */
    private String buildSql(MappedStatement ms, Object parameter) {
        BoundSql boundSql;
        try {
            boundSql = ms.getBoundSql(parameter);
        } catch (Exception e) {
            // 动态 SQL 求值本身出错时不该再拖慢日志路径，把原因写出来即可
            return "(取不到 SQL：" + e.getClass().getSimpleName() + ")";
        }
        String raw = boundSql.getSql();
        List<ParameterMapping> mappings = boundSql.getParameterMappings();
        if (raw == null || raw.isEmpty()) {
            return "(空 SQL)";
        }
        if (mappings == null || mappings.isEmpty()) {
            return truncate(collapseWhitespace(raw));
        }

        Configuration configuration = ms.getConfiguration();
        StringBuilder out = new StringBuilder(raw.length() + 32);
        int index = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' || c == '"') {
                // 引号内原样搬运：字面量里的 ? 不是占位符，替进去就把 SQL 改坏了
                i = copyQuoted(raw, i, out) - 1;
                continue;
            }
            if (c == '/' && raw.startsWith("/*", i)) {
                int end = raw.indexOf("*/", i + 2);
                end = end < 0 ? raw.length() : end + 2;
                out.append(raw, i, end);
                i = end - 1;
                continue;
            }
            if (c == '-' && raw.startsWith("--", i)) {
                int end = raw.indexOf('\n', i);
                end = end < 0 ? raw.length() : end;
                out.append(raw, i, end);
                i = end - 1;
                continue;
            }
            if (c == '?' && index < mappings.size()) {
                String property = mappings.get(index++).getProperty();
                out.append(formatValue(property, resolveValue(boundSql, configuration, property)));
                continue;
            }
            out.append(Character.isWhitespace(c) ? ' ' : c);
        }
        if (index < mappings.size()) {
            // 参数映射比替换掉的 ? 多：要么 SQL 里真有字面 ?，要么某个 ? 被引号/注释判据吃掉了，
            // 这时候内联出来的语句不可信，必须把异常显式标在行尾，不能默默给出一条错的 SQL
            return truncate(out + " ...(参数映射多于占位符，后面未替换，本条 SQL 不可直接复现)");
        }
        return truncate(out.toString());
    }

    /**
     * 把一段引号包裹的内容原样搬到 out，返回闭合引号之后的下标。
     *
     * <p>两种转义都要认：MySQL 默认允许反斜杠转义（{@code \'"} 不结束字符串），而 SQL 标准写法
     * 是用两个连续引号表示引号本身（{@code ''}）。漏掉任一种都会让后面的 {@code ?} 错位。
     */
    private int copyQuoted(String sql, int start, StringBuilder out) {
        char quote = sql.charAt(start);
        out.append(quote);
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                out.append(c).append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    out.append(quote).append(quote);
                    i += 2;
                    continue;
                }
                out.append(c);
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return i;
    }

    /**
     * 按参数映射给出的属性名取实际值。三种入参形态都要覆盖，否则日志里会一片 {@code null}：
     * foreach 展开出来的临时参数（放在 additionalParameter 里）、Map 型入参、以及 POJO 入参。
     */
    private Object resolveValue(BoundSql boundSql, Configuration configuration, String property) {
        if (boundSql.hasAdditionalParameter(property)) {
            return boundSql.getAdditionalParameter(property);
        }
        Object param = boundSql.getParameterObject();
        if (param == null) {
            return null;
        }
        TypeHandlerRegistry registry = configuration.getTypeHandlerRegistry();
        if (registry.hasTypeHandler(param.getClass())) {
            // 单参数且没被包成 Map（例如 selectById(1L)）：parameterObject 本身就是这一个值
            return param;
        }
        try {
            if (param instanceof Map<?, ?> map && map.containsKey(property)) {
                // 用 containsKey 而不是直接 get：MapperMethod.ParamMap 对缺失 key 会抛 BindingException
                return map.get(property);
            }
            MetaObject metaObject = SystemMetaObject.forObject(param);
            return metaObject.hasGetter(property) ? metaObject.getValue(property) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把参数值渲染成能直接贴进 SQL 的片段。
     *
     * <p>凭证类字段按<b>属性名</b>遮掉：慢 SQL 日志记录的是真实参数值，
     * 改密链路的 {@code password} 一旦带进来，这个文件就成了密码清单。
     * 与 {@code WebLogAspect} 共用 {@link ImConstants#SENSITIVE_KEY_WORDS}，两边认同一批关键词。
     */
    private String formatValue(String property, Object value) {
        if (isSensitive(property)) {
            return MASKED_VALUE;
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return "0x(" + bytes.length + "B)";
        }
        if (value instanceof Collection<?> collection) {
            // in (...) 展开前的集合型入参，加引号反而不像 SQL
            return truncate(collection.toString(), MAX_VALUE_LENGTH);
        }
        String text = String.valueOf(value);
        if (value instanceof LocalDateTime) {
            // LocalDateTime.toString() 用 T 分隔，MySQL 认空格分隔的写法，直接可贴
            text = text.replace('T', ' ');
        }
        return "'" + escapeQuote(collapseWhitespace(truncate(text, MAX_VALUE_LENGTH))) + "'";
    }

    private boolean isSensitive(String property) {
        String lower = property.toLowerCase(Locale.ROOT);
        for (String word : ImConstants.SENSITIVE_KEY_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String escapeQuote(String text) {
        return text.indexOf('\'') < 0 ? text : text.replace("'", "''");
    }

    /** 压成一行：多行 SQL 在日志里 grep 一次只能捞到半句 */
    private String collapseWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace && !out.isEmpty()) {
                    out.append(' ');
                }
                lastSpace = true;
            } else {
                out.append(c);
                lastSpace = false;
            }
        }
        return out.toString().trim();
    }

    private String truncate(String text) {
        return truncate(text, MAX_SQL_LENGTH);
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...(截断,共" + text.length() + "字符)";
    }
}
