package com.im.ai.service;

import com.im.ai.config.ImAiProperties;
import com.im.ai.dto.vo.WebResultVO;
import com.im.ai.dto.vo.WebSearchVO;
import com.im.common.constant.RedisKeys;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全网（互联网）关键字检索，供消息搜索框的「网络」分组使用。
 *
 * <p>实现方式是抓取搜索引擎的结果页并解析 HTML，而不是接一个搜索 API：
 * 主流的网页搜索接口都要密钥与配额，本项目没有；Bing 结果页在国内可直连，
 * 结构近年稳定。代价是解析规则绑在对方的 class 名上，对方改版就会拿不到结果——
 * 所以所有失败路径都收敛成「空列表」而不是异常：网络分组显示不出来，
 * 聊天消息的本地检索必须照常工作。抓取入口与超时在 {@code im.ai.web-search} 下可配。
 *
 * <p>结果按关键字缓进 Redis（{@code im:web:search:{关键字 MD5}}），
 * 缓存读写失败一律忽略：Redis 不可用时退化成每次都真抓取。
 */
@Slf4j
@Service
public class WebSearchService {

    /** 数据来源标识，前端与排查日志都用它判断是哪套解析规则 */
    private static final String PROVIDER = "bing";

    /**
     * 一条结果 = 一个 {@code <li class="b_algo">}。
     *
     * <p>结束边界刻意用「下一个结果块的起点」而不是非贪婪匹配到 {@code </li>}：
     * 带站点子链接的结果内部还有 {@code <li>}，匹配到 {@code </li>} 会把摘要截没。
     */
    private static final Pattern BLOCK = Pattern.compile(
            "(?s)<li\\s+class=\"b_algo[^\"]*\".*?(?=<li\\s+class=\"b_algo|<li\\s+class=\"b_ad|</ol>|$)");

    /** 标题与其链接：{@code <h2 ...><a href="真实地址">标题</a></h2> */
    private static final Pattern TITLE_LINK = Pattern.compile(
            "(?s)<h2[^>]*>\\s*<a[^>]*?href=\"([^\"]*)\"[^>]*>(.*?)</a>");

    /** 站点名，取不到时退到 {@code <cite>} 那行面包屑 */
    private static final Pattern SITE_NAME = Pattern.compile("(?s)<div class=\"tptt\"[^>]*>(.*?)</div>");
    private static final Pattern SITE_CITE = Pattern.compile("(?s)<cite[^>]*>(.*?)</cite>");

    /** 摘要：优先带省略行数样式的 p，退到整个 b_caption */
    private static final Pattern SNIPPET_LINE = Pattern.compile("(?s)<p class=\"b_lineclamp[^\"]*\"[^>]*>(.*?)</p>");
    private static final Pattern SNIPPET_CAPTION = Pattern.compile("(?s)<div class=\"b_caption[^\"]*\"[^>]*>(.*?)</div>");

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    /** 空白折叠：一并吃掉结果页里的 NBSP、en/em 空格等排版空白 */
    private static final Pattern BLANKS = Pattern.compile("[\\s\\u00A0\\u2000-\\u200F\\u3000]+");
    /** Bing 偶尔给的是跳转壳 {@code .../ck/a?...&u=a1<base64>}，解出来才是真实地址 */
    private static final Pattern REDIRECT_PARAM = Pattern.compile("[?&]u=a1([A-Za-z0-9+/_=-]+)");

    private final ImAiProperties properties;
    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final HttpClient httpClient;

    public WebSearchService(ImAiProperties properties, RedisUtil redisUtil, JsonUtil jsonUtil) {
        this.properties = properties;
        this.redisUtil = redisUtil;
        this.jsonUtil = jsonUtil;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 结果页会 302 到 www.bing.com，不跟随重定向就只能拿到空壳
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 检索关键字。
     *
     * <p>返回的 {@code results} 为空表示「没有网络结果」，不表示失败：
     * 未启用、关键字清洗后为空、抓取超时、解析不到，四种情形都只记日志不上抛。
     */
    public WebSearchVO search(String rawKeyword) {
        ImAiProperties.WebSearch config = properties.getWebSearch();
        String keyword = normalizeKeyword(rawKeyword, config.getMaxKeywordChars());
        if (keyword == null) {
            return emptyResult(rawKeyword, config);
        }
        if (!config.isEnabled()) {
            return emptyResult(keyword, config);
        }

        String cacheKey = RedisKeys.webSearch(md5(keyword));
        WebSearchVO cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<WebResultVO> results = fetch(keyword, config);
        WebSearchVO vo = WebSearchVO.builder()
                .keyword(keyword)
                .provider(PROVIDER)
                .moreUrl(config.getEndpoint() + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8))
                .results(results)
                .build();
        writeCache(cacheKey, vo, config);
        return vo;
    }

    /* ------------------------------ 抓取与解析 ------------------------------ */

    private List<WebResultVO> fetch(String keyword, ImAiProperties.WebSearch config) {
        URI uri = URI.create(config.getEndpoint() + "?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&count=" + Math.min(config.getMaxResults(), 30) + "&setlang=zh-hans");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                // 伪装成浏览器：服务端 UA 会被结果页换成验证页，解析不到任何条目
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET()
                .build();

        String html;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("全网检索结果页状态码异常: {} keyword={}", response.statusCode(), keyword);
                return List.of();
            }
            html = response.body();
        } catch (IOException e) {
            // 超时与断连是最常见的失败（外网抖动、被对方限流），只记 warn 不打堆栈
            log.warn("全网检索抓取失败: {} keyword={}", e.getMessage(), keyword);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("全网检索被中断: keyword={}", keyword);
            return List.of();
        }
        return parse(html, config);
    }

    /** 把结果页 HTML 解析成条目；解析不到就返回空列表，规则变了也只是这一栏空白 */
    private List<WebResultVO> parse(String html, ImAiProperties.WebSearch config) {
        List<WebResultVO> results = new ArrayList<>();
        if (TextUtil.isBlank(html)) {
            return results;
        }
        Set<String> seenUrls = new HashSet<>();
        Matcher blocks = BLOCK.matcher(html);
        while (blocks.find() && results.size() < config.getMaxResults()) {
            String block = blocks.group();
            Matcher link = TITLE_LINK.matcher(block);
            if (!link.find()) {
                continue;
            }
            String url = cleanUrl(link.group(1));
            String title = plainText(link.group(2), config.getMaxSnippetChars() * 2);
            if (url == null || title == null || !seenUrls.add(url)) {
                continue;
            }
            results.add(WebResultVO.builder()
                    .title(title)
                    .url(url)
                    .site(siteOf(block, url))
                    .snippet(plainText(firstGroup(block, SNIPPET_LINE, SNIPPET_CAPTION), config.getMaxSnippetChars()))
                    .build());
        }
        if (results.isEmpty()) {
            log.warn("全网检索未解析到结果，可能是结果页结构变更: htmlLength={}", html.length());
        }
        return results;
    }

    /** 依次用多个正则试第一个捕获组，都取不到返回 {@code null} */
    private String firstGroup(String block, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(block);
            if (matcher.find() && TextUtil.isNotBlank(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** 站点名：优先结果页自带的域名，兜底用真实 URL 的 host */
    private String siteOf(String block, String url) {
        String site = plainText(firstGroup(block, SITE_NAME, SITE_CITE), 60);
        if (site != null) {
            // <cite> 里是「https://www.runoob.com ? java ? 教程」这种面包屑，只要第一段
            int cut = site.indexOf(' ');
            site = cut > 0 ? site.substring(0, cut) : site;
        }
        if (site == null || site.isBlank()) {
            try {
                site = URI.create(url).getHost();
            } catch (Exception e) {
                site = null;
            }
        }
        if (site == null) {
            return null;
        }
        return site.startsWith("www.") ? site.substring(4) : site;
    }

    /**
     * 去标签 → 解 HTML 实体 → 折叠空白 → 截断。
     *
     * <p>顺序不能颠倒：先解实体再 strip 标签会让摘要里的 {@code &lt;} 变成真尖括号而被当成标签吃掉。
     */
    private String plainText(String html, int maxLength) {
        if (TextUtil.isBlank(html)) {
            return null;
        }
        String text = HtmlUtils.htmlUnescape(HTML_TAG.matcher(html).replaceAll(""));
        text = BLANKS.matcher(text).replaceAll(" ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }

    /**
     * 规范化链接：解 HTML 实体、拆 Bing 跳转壳、只留 http/https、剔掉站内链接。
     *
     * @return 不合法的地址返回 {@code null}，调用方直接丢弃该条结果
     */
    private String cleanUrl(String rawUrl) {
        if (TextUtil.isBlank(rawUrl)) {
            return null;
        }
        String url = HtmlUtils.htmlUnescape(rawUrl.trim());
        Matcher redirect = REDIRECT_PARAM.matcher(url);
        if (url.contains("/ck/a") && redirect.find()) {
            String decoded = decodeRedirect(redirect.group(1));
            if (decoded != null) {
                url = decoded;
            }
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        // 搜索结果里混进的 bing/microsoft 自身链接（广告位、"查看更多"）不是用户想要的答案
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null || host.endsWith("bing.com") || host.endsWith("microsoft.com") || host.endsWith("msn.cn")) {
            return null;
        }
        // 统一走 https：明文 http 链接在新标签页里会被浏览器标记为不安全
        return url.replaceFirst("^http://", "https://");
    }

    /** {@code u=a1<base64>}：去掉 {@code a1} 前缀后按 URL-safe base64 还原真实地址 */
    private String decodeRedirect(String param) {
        try {
            String base64 = param.startsWith("a1") ? param.substring(2) : param;
            String url = new String(Base64.getUrlDecoder().decode(base64), StandardCharsets.UTF_8);
            return url.startsWith("http") ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------ 清洗与缓存 ------------------------------ */

    /** 清洗关键字：去脚本片段、去标签、折叠空白、截断；清洗后为空返回 {@code null} */
    private String normalizeKeyword(String raw, int maxLength) {
        String keyword = plainText(TextUtil.sanitize(raw), maxLength);
        return TextUtil.isBlank(keyword) ? null : keyword;
    }

    private String md5(String keyword) {
        return DigestUtils.md5DigestAsHex(keyword.getBytes(StandardCharsets.UTF_8));
    }

    private WebSearchVO readCache(String cacheKey) {
        try {
            return jsonUtil.fromJsonQuietly(redisUtil.get(cacheKey), WebSearchVO.class);
        } catch (Exception e) {
            log.debug("全网检索读缓存失败，退化为直接抓取: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 只缓存非空结果：抓空多半是超时或被限流，缓存下来会让这个关键字在 TTL 内一直空白。
     */
    private void writeCache(String cacheKey, WebSearchVO vo, ImAiProperties.WebSearch config) {
        if (vo.getResults().isEmpty() || config.getCacheSeconds() <= 0) {
            return;
        }
        try {
            redisUtil.set(cacheKey, jsonUtil.toJson(vo), Duration.ofSeconds(config.getCacheSeconds()));
        } catch (Exception e) {
            log.debug("全网检索写缓存失败，忽略: {}", e.getMessage());
        }
    }

    private WebSearchVO emptyResult(String keyword, ImAiProperties.WebSearch config) {
        return WebSearchVO.builder()
                .keyword(keyword)
                .provider(config.isEnabled() ? PROVIDER : "disabled")
                .results(List.of())
                .build();
    }
}
