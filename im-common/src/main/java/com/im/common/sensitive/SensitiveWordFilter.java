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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感词过滤器：DFA 字典树 + 匹配过程中原地跳过干扰字符。
 *
 * <p>词库来自 classpath {@code wordlists/sensitive_words.txt}（顿号 / 逗号 / 换行混合分隔），
 * 启动时一次性清洗建 Trie：只保留「汉字 + 小写字母 + 数字」构成的词（大小写折叠后入库），
 * 带正则元字符、乱码或单字的条目全部丢弃——单字词（如「了」「人」）在正常聊天里
 * 误伤率远超收益，而 {@code 词A.{0,6}词B} 这类正则条目不属于字典树的表达能力。
 *
 * <p>「原地跳干扰字符」是 IM 场景的关键：把词拆开写成 {@code 字1*字2~字3}、
 * {@code 字1 字2 字3} 这类插入标点与空格的变体，与原词同样命中，命中区间连同中间的干扰符一起遮成 {@code *}。
 * 判定「干扰」用「非字母且非数字」一刀切（含全角折叠后判断），比维护一份
 * 干扰符号白名单更稳：新冒出来的花式分隔符天然被跳过。
 *
 * <p>装载失败不阻断启动：词库是增强功能不是核心链路，快照为空（{@code ready} 为 false）时
 * {@link #mask(String)} 原样返回，宁可放行也不能把全服聊天打挂。
 *
 * <p>开关是运行时的：初始值取自配置 im.message.sensitive-filter-enabled，之后可经后台
 * {@code /api/admin/config/sensitive-filter}（要 {@code system:config}）热切换，改完立即生效、不用重启。
 * 本类只认 {@link #setEnabled} 这一件事，<b>不管落库</b>：四期第一批时这一笔只改内存（重启回到配置文件的值，
 * 聊天端「设置 › 高级设置」里那个入口也已收回后台），第四批起它进了 {@code im_config}，
 * 重启后由 im-admin 的同步任务把库里那一行盖回来。
 *
 * <h2>后台四期第四批：词库可以从库里长出来，但文件仍然是底</h2>
 *
 * <p>{@link #reload(java.util.Collection)} 拿「后台词库的全部条目」与文件词条<b>求并集后整棵重建</b>，
 * 而不是往现役树上插节点。两个选择都有代价，取后者是因为：
 * 插节点无法处理「删词」——一个词在树上往往是另一个词的中间路径（「研究」与「研究人员」），
 * 摘掉 end 标志时稍不留意就把另一个词一起弄没了，而重建不存在这个问题。
 * 一万九千余条重建只要几十毫秒（启动日志里那 145ms 还包括读文件与解码），且只在词条真的变过时才重建。
 *
 * <p>并集的<b>底必须是 {@link #fileWords} 那份文件词条，不能是现役快照</b>：现役快照里已经掺着
 * 上一次 reload 合进来的后台词，拿它当底的话，后台删掉一个词、重建时又把它并回来，
 * 于是那个词永远删不掉。这是一个只看代码看不出、一测就中的坑，所以单独写在这里。
 *
 * <p>「求并集」而不是「以库为准」是个不能省的口径：文件那 19643 条是底，
 * 库里只有后台手动加的几十条。要是 reload 只读库，一加词全服词库瞬间从一万九跌到几十。
 * 反过来说，<b>文件里的词在后台只能加不能减</b>，要摘一个词得改代码重新打包（已知边界）。
 *
 * <p>并发上只有一个关键：{@code mask()} 跑在所有发消息的线程上，不能加锁。所以快照是
 * 一个不可变对象挂在 volatile 字段里，读路径开头取一次引用、之后全程用它——
 * 要的是「这一条消息自始至终看到的是同一棵树」，而不是「树不会变」。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 词条分隔符：中文顿号、中英文逗号、换行 */
    private static final Pattern SEPARATORS = Pattern.compile("[、，,\\r\\n]+");

    /** 合法词条：至少 2 个字符，仅汉字与小写字母数字（入库前已折叠大小写） */
    private static final Pattern VALID_WORD = Pattern.compile("^[\\p{IsHan}a-z0-9]{2,}$");

    private static final String WORDLIST_PATH = "wordlists/sensitive_words.txt";

    /**
     * 当前词库快照：由 {@link #fileWords} ∪ 后台词条建出的字典树。
     *
     * <p>字段不再是 {@code final}：它指向的对象一旦发布就永不修改，换词库时换的是整个引用。
     */
    private volatile Lexicon lexicon = Lexicon.empty();

    /**
     * 文件词库那份不可变的底（{@link #init()} 装载后不再变）。
     *
     * <p>{@link #reload} 每次都从这里出发拼并集，这样「库里删掉的词」才会真的从树上消失。
     */
    private volatile Set<String> fileWords = Set.of();

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

    /** 不可变词库快照：{@link #root} 由当次生效的词条集建出，{@link #size} 是生效条数 */
    private static final class Lexicon {
        final Node root;
        final int size;

        Lexicon(Node root, int size) {
            this.root = root;
            this.size = size;
        }

        static Lexicon empty() {
            return new Lexicon(new Node(), 0);
        }
    }

    /**
     * 装载文件词库并建字典树。全程不读 {@code im_config}、不查库：
     * 本类在 im-common，不该知道后台的存在，库里的词由 im-admin 通过 {@link #reload} 推进来。
     */
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
            Set<String> words = new LinkedHashSet<>();
            int skipped = 0;
            for (String token : SEPARATORS.split(raw)) {
                String word = fold(token.trim());
                if (word.isEmpty()) {
                    continue;
                }
                if (VALID_WORD.matcher(word).matches()) {
                    words.add(word);
                } else {
                    skipped++;
                }
            }
            this.fileWords = Set.copyOf(words);
            this.lexicon = build(words);
            log.info("[敏感词] 词库装载完成：生效 {} 条，丢弃 {} 条，耗时 {}ms",
                    lexicon.size, skipped, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[敏感词] 词库装载失败，过滤器保持关闭", e);
        }
    }

    /**
     * 命中即遮：每个被命中的字符（含词内干扰符）替换为等长的 {@code *}，未命中原文返回。
     *
     * <p>开头那一句 {@code Lexicon current = this.lexicon;} 是全方法唯一读 volatile 的地方：
     * 把快照收拢成一个局部变量，中间即使定时任务换了一棵树，本条消息也仍然按开头那棵走完，
     * 不会出现「前半句用旧树、后半句用新树」这种比遮错词更难解释的结果。
     */
    public String mask(String text) {
        Lexicon current = this.lexicon;
        if (current.size == 0 || !enabled || text == null || text.isEmpty()) {
            return text;
        }
        Node root = current.root;
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

    /** 词库是否可用（快照里真有条目）；后台配置页拿它区分「没配词」和「配了但没装载成功」 */
    public boolean isReady() {
        return lexicon.size > 0;
    }

    /** 当前过滤开关状态（接口回填前端开关用） */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 热切换过滤开关：只改运行态。落库由调用方负责（后台四期第四批之后，
     * {@code im_config} 里那条才是跨重启的权威，重启后由 im-admin 的生效器盖回来）。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[敏感词] 过滤开关切换为 {}", enabled ? "开启" : "关闭");
    }

    /**
     * 当前生效词条数（文件词库 + 后台词条合并去重后的总数）。后台把这个数字显示出来，
     * 是为了让「改了没生效」能被看出来。
     */
    public int getWordCount() {
        return lexicon.size;
    }

    /**
     * 把后台词库（{@code im_sensitive_word}）合进来并整棵重建字典树。
     *
     * <p>传进来的是<b>后台那一侧的全部生效词条</b>而不是增量：新树 = {@code fileWords} ∪ 本参数，
     * 所以后台删掉的词不会因为「没被提到」而留在树上。传空集就等价于「回到只有文件词库」。
     *
     * <p>底之所以取 {@link #fileWords} 而不是现役快照：现役快照里掺着上一次合进来的后台词，
     * 以它为底重建会让删除永不生效（删掉的那个词下一次重建时又被并回来）。
     *
     * <p>幂等：同一批词条重复调只多一次重建，出来的生效条数不变。失败方向安全：
     * 重建过程中旧快照仍在 volatile 上顶着，只有新树建完整了才换引用，
     * 中途抛异常不会把全服词库换成一棵空树。
     *
     * @param extraWords 后台词库的词条（未折叠也可以，本方法会折叠并重新校验）
     * @return 重建后的生效词条数，同 {@link #getWordCount()}
     */
    public int reload(Collection<String> extraWords) {
        Set<String> words = new LinkedHashSet<>(fileWords);
        int invalid = 0;
        if (extraWords != null) {
            for (String item : extraWords) {
                if (item == null) {
                    continue;
                }
                String word = fold(item.trim());
                if (word.isEmpty()) {
                    continue;
                }
                if (VALID_WORD.matcher(word).matches()) {
                    words.add(word);
                } else {
                    invalid++;
                }
            }
        }
        Lexicon previous = this.lexicon;
        Lexicon built = build(words);
        this.lexicon = built;
        log.info("[敏感词] 词库重建完成：{} -> {} 条（后台传入 {} 条，其中不合法丢弃 {} 条）",
                previous.size, built.size, extraWords == null ? 0 : extraWords.size(), invalid);
        return built.size;
    }

    /**
     * 折叠整串并按分隔符拆词：供后台批量导入用。
     *
     * <p>拆词规则（顿号 / 中英文逗号 / 换行）只有本类知道，不能拿到调用方再拼一份正则——
     * 文件词库本身就是这套分隔符装进来的，后台导入用另一套会导致「同一个文件贴进去两边结果不同」。
     * 返回的是<b>折叠后</b>的 token，合法性由调用方逐条用 {@link #isValidWord} 判定（它需要分开计数）。
     */
    public static List<String> splitTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : SEPARATORS.split(raw)) {
            String word = fold(token.trim());
            if (!word.isEmpty()) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /** 单个词条的折叠（去空白 + 全角转半角 + 统一小写），后台入库前必须先过这一步 */
    public static String foldWord(String raw) {
        return raw == null ? "" : fold(raw.trim());
    }

    /**
     * 折叠后的词条是否合法：至少 2 个字符且只含汉字与小写字母数字。
     *
     * <p>公开它是为了让后台用与装载文件词库完全同一道闸门，而不是在 im-admin 里养一份松一倍的校验。
     */
    public static boolean isValidWord(String foldedWord) {
        return foldedWord != null && VALID_WORD.matcher(foldedWord).matches();
    }

    /**
     * 由词条集建一棵全新的字典树。只在 {@link #init()} 与 {@link #reload} 里调，
     * 建完才发布，所以建树期间读方看到的一直是旧快照。
     */
    private static Lexicon build(Set<String> words) {
        Node root = new Node();
        int added = 0;
        for (String word : words) {
            if (insert(root, word)) {
                added++;
            }
        }
        return new Lexicon(root, added);
    }

    /**
     * 词条入树；完全重复的词条（词库里不少）只会第一次成功。
     */
    private static boolean insert(Node root, String word) {
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
