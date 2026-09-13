package com.im.common.web;

import cn.dev33.satoken.stp.StpUtil;
import com.im.common.constant.ImConstants;
import com.im.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller 层访问日志切面。
 *
 * <p>记录请求方法、URI、客户端 IP、入参、耗时与出参摘要，便于排障与审计。
 * 同时对文件流、Servlet 原生对象等无法/不宜序列化的入参做降级处理。
 *
 * <p>入参与出参在落日志前会按字段名脱敏（见 {@link #SENSITIVE_KEY_WORDS}）：
 * 注册/登录/改密的请求体里有明文密码，登录成功的返回值里有完整 token，
 * 这两类凭证都不允许以明文进入日志文件。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class WebLogAspect {

    /** 出参摘要最大长度，超出截断，避免大列表刷屏 */
    private static final int MAX_RESULT_LENGTH = 1000;

    /**
     * 敏感字段名关键词：字段名转小写后包含任一关键词，值就遮成 {@link #MASKED_VALUE}。
     *
     * <p>用子串匹配而不是精确匹配，是为了覆盖 oldPassword / newPassword / accessToken
     * 这类变体，新增字段时也不用记得来改这里。
     */
    private static final List<String> SENSITIVE_KEY_WORDS = List.of(
            "password", "passwd", "token", "secret", "captcha", "smscode",
            "verifycode", "credential", "ticket", "debugcode");

    private static final String MASKED_VALUE = "***";

    private final JsonUtil jsonUtil;

    @Pointcut("execution(public * com.im..controller..*.*(..))")
    public void controllerMethod() {
    }

    @Around("controllerMethod()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        putUserIdToMdc();
        HttpServletRequest request = currentRequest();
        String signature = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "#" + joinPoint.getSignature().getName();
        String uri = request == null ? "-" : request.getMethod() + " " + request.getRequestURI();
        String ip = request == null ? "-" : clientIp(request);

        long start = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug(">> {} | {} | ip={} | args={}", uri, signature, ip, formatArgs(joinPoint.getArgs()));
        }
        try {
            Object result = joinPoint.proceed();
            long cost = System.currentTimeMillis() - start;
            if (log.isDebugEnabled()) {
                log.debug("<< {} | {} | cost={}ms | result={}", uri, signature, cost, summarize(result));
            } else {
                log.info("{} {} cost={}ms", uri, signature, cost);
            }
            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;
            // 业务异常属于预期流程，交给全局异常处理器输出，这里只记 warn 保留耗时信息
            log.warn("!! {} | {} | cost={}ms | error={}", uri, signature, cost, e.getMessage());
            throw e;
        }
    }

    /**
     * 请求进入 Controller 时把当前登录用户写入 MDC，日志 pattern 中即可打印。
     */
    private void putUserIdToMdc() {
        try {
            if (StpUtil.isLogin()) {
                MDC.put(ImConstants.MDC_USER_ID, String.valueOf(StpUtil.getLoginIdAsLong()));
            }
        } catch (Exception ignored) {
            // 未登录接口（注册/登录/验证码）取不到属正常情况
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args)
                .map(this::describeArg)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String describeArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        if (arg instanceof MultipartFile file) {
            return "MultipartFile(" + file.getOriginalFilename() + "," + file.getSize() + "B)";
        }
        if (arg instanceof MultipartFile[] files) {
            return "MultipartFile[" + files.length + "]";
        }
        if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse) {
            return arg.getClass().getSimpleName();
        }
        return summarize(arg);
    }

    private String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        String text;
        try {
            text = jsonUtil.toJson(maskSensitive(value));
        } catch (Exception e) {
            text = String.valueOf(value);
        }
        if (text == null) {
            text = String.valueOf(value);
        }
        return text.length() > MAX_RESULT_LENGTH
                ? text.substring(0, MAX_RESULT_LENGTH) + "...(truncated," + text.length() + ")"
                : text;
    }

    /**
     * 把对象转成可变的 Map/List 树，按字段名遮掉敏感值后再交给序列化。
     *
     * <p>不在序列化之后用正则替换 JSON 文本：密码里若含转义引号，正则会提前截断，
     * 反而把密码的后半段留在日志里。结构化处理没有这个风险。
     * 转换失败时原样返回——日志降级可以，脱敏逻辑不能反过来影响请求。
     */
    private Object maskSensitive(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        try {
            Object tree = jsonUtil.mapper().convertValue(value, Object.class);
            maskTree(tree);
            return tree;
        } catch (Exception e) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private void maskTree(Object node) {
        if (node instanceof Map<?, ?> raw) {
            // convertValue 产出的 JSON 对象键必然是 String，值必然是 Object 子树，这个 cast 是安全的；
            // 用 Map<?, ?> 遍历则 entry.setValue 会被通配符 capture 拒绝
            Map<Object, Object> map = (Map<Object, Object>) raw;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object child = entry.getValue();
                if (entry.getKey() instanceof String key && isSensitiveKey(key)) {
                    // 值为 null 时保留 null：遮成 *** 反而像「密码是空串被遮了」
                    if (child != null) {
                        entry.setValue(MASKED_VALUE);
                    }
                } else {
                    maskTree(child);
                }
            }
        } else if (node instanceof List<?> list) {
            // 数组元素没有字段名可判断，只递归里面的对象
            for (Object child : list) {
                maskTree(child);
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String word : SENSITIVE_KEY_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
