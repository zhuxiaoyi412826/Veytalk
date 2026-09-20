package com.im.common.sensitive;

import com.im.common.config.ImProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 敏感词过滤器：DFA 字典树 + 匹配过程中原地跳过干扰字符。
 *
 * <p>词库来自 classpath {@code wordlists/sensitive_words.txt}（顿号 / 逗号 / 换行混合分隔），
 * 启动时一次性清洗建 Trie：只保留「汉字 + 小写字母 + 数字」构成的词（大小写折叠后入库），
 * 带正则元字符、乱码或单字的条目全部丢弃——单字词（如「平」「日」）在正常聊天里
 * 误伤率远超收益，而 {@code 法.{0,6}轮} 这类正则条目不属于字典树的表达能力。
 *
 * <p>「原地跳干扰字符」是 IM 场景的关键：{@code 法*轮~功}、{@code 法 轮 功} 这类
 * 插入标点的空格变体与原词同样命中，命中区间连同中间的干扰符一起遮成 {@code *}。
 * 判定「干扰」用「非字母且非数字」一刀切（含全角折叠后判断），比维护一份
 * 干扰符号白名单更稳：新冒出来的花式分隔符天然被跳过。
 *
 * <p>装载失败不阻断启动：词库是增强功能不是核心链路，{@link #ready} 为 false 时
 * {@link #mask(String)} 原样返回，宁可放行也不能把全服聊天打挂。
 *
 * <p>开关是运行时的：初始值取自配置 im.message.sensitive-filter-enabled，
 * 之后可经接口 {@code setEnabled} 热切换（前端「高级设置-调试」入口），
 * 改完立即生效、不用重启；重启后回到配置文件的值。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 词条分隔符：中文顿号、中英文逗号、换行 */
    private static final Pattern SEPARATORS = Pattern.compile("[、，,\\r\\n]+");

    /** 合法词条：至少 2 个字符，仅汉字与小写字母数字（入库前已折叠大小写） */
    private static final Pattern VALID_WORD = Pattern.compile("^[\\p{IsHan}a-z0-9]{2,}$");

    private static final String WORDLIST_PATH = "wordlists/sensitive_words.txt";

    /** 根节点不参与匹配，仅持有第一层字符的出边 */
    private final Node root = new Node();

    private volatile boolean ready;

    /** 运行时过滤开关：mask() 入口判断，关闭时直接原样放行 */
    private volatile boolean enabled = true;

    private final ImProperties imProperties;

    public SensitiveWordFilter(ImProperties imProperties) {
        this.imProperties = imProperties;
    }

    private static final class Node {
        /** 绝大多数节点只有一两个子节点，Map 初始容量给 2 省内存 */
        final Map<Character, Node> children = new HashMap<>(2);
        boolean end;
    }

    @PostConstruct
    public void init() {
        enabled = imProperties.getMessage().isSensitiveFilterEnabled();
        long start = System.currentTimeMillis();
        try (InputStream in = SensitiveWordFilter.class.getClassLoader().getResourceAsStream(WORDLIST_PATH)) {
            if (in == null) {
                log.warn("[敏感词] 未找到词库 {}，过滤器保持关闭", WORDLIST_PATH);
                return;
            }
            String raw = decode(in.readAllBytes());
            int added = 0;
            int skipped = 0;
            for (String token : SEPARATORS.split(raw)) {
                String word = fold(token.trim());
                if (word.isEmpty()) {
                    continue;
                }
                if (VALID_WORD.matcher(word).matches()) {
                    if (insert(word)) {
                        added++;
                    }
                } else {
                    skipped++;
                }
            }
            ready = added > 0;
            log.info("[敏感词] 词库装载完成：生效 {} 条，丢弃 {} 条，耗时 {}ms",
                    added, skipped, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[敏感词] 词库装载失败，过滤器保持关闭", e);
        }
    }

    /**
     * 命中即遮：每个被命中的字符（含词内干扰符）替换为等长的 {@code *}，未命中原文返回。
     */
    public String mask(String text) {
        if (!ready || !enabled || text == null || text.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        int len = chars.length;
        int i = 0;
        boolean changed = false;
        while (i < len) {
            char first = normalize(chars[i]);
            if (isNoise(first)) {
                i++;
                continue;
            }
            Node state = root;
            int j = i;
            int matchedEnd = -1;
            while (j < len) {
                char c = normalize(chars[j]);
                if (isNoise(c)) {
                    // 尚未匹配任何有效字符时出现的噪声交给外层推进；匹配途中的噪声原地跳过
                    if (state == root) {
                        break;
                    }
                    j++;
                    continue;
                }
                Node next = state.children.get(c);
                if (next == null) {
                    break;
                }
                state = next;
                j++;
                if (state.end) {
                    // 记最长匹配：后面还可能更长，继续推进直到走不动
                    matchedEnd = j;
                }
            }
            if (matchedEnd > 0) {
                for (int k = i; k < matchedEnd; k++) {
                    chars[k] = '*';
                }
                changed = true;
                i = matchedEnd;
            } else {
                i++;
            }
        }
        return changed ? new String(chars) : text;
    }

    public boolean isReady() {
        return ready;
    }

    /** 当前过滤开关状态（接口回填前端开关用） */
    public boolean isEnabled() {
        return enabled;
    }

    /** 热切换过滤开关：只改内存标志，不落配置不改启动参数 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[敏感词] 过滤开关切换为 {}", enabled ? "开启" : "关闭");
    }

    /**
     * 词条入树；完全重复的词条（词库里不少）只会第一次成功。
     */
    private boolean insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), key -> new Node());
        }
        if (node.end) {
            return false;
        }
        node.end = true;
        return true;
    }

    /**
     * 折叠字符：全角 ASCII 转半角后统一小写。
     *
     * <p>词库里混着 {@code ＵＲ} 这类全角写法，不折叠的话发送 {@code ur} 就漏检。
     */
    private static char normalize(char c) {
        if (c >= '\uFF01' && c <= '\uFF5E') {
            c = (char) (c - 0xFEE0);
        }
        return Character.toLowerCase(c);
    }

    /** 整串折叠，供装载词条用 */
    private static String fold(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = normalize(chars[i]);
        }
        return new String(chars);
    }

    /** 字母与数字之外的字符都视为可跳过的干扰符（含空白、标点、符号、零宽字符） */
    private static boolean isNoise(char c) {
        return !Character.isLetterOrDigit(c);
    }

    /**
     * 词库编码自适应：优先严格 UTF-8（跳过 BOM），解不动再按 GB18030 兜底。
     *
     * <p>敏感词文件来自外部整理，来源混杂下编码没有保证；严格解码失败即说明
     * 不是 UTF-8，用国内 Windows 导出的 GB 系编码重试一次即可覆盖实际情况。
     */
    private static String decode(byte[] bytes) throws CharacterCodingException {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, Charset.forName("GB18030"));
        }
    }
}
