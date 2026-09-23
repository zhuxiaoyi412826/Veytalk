package com.im.ai.rag;

import com.im.ai.config.ImAiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库检索服务（RAG 的 R）。
 *
 * <h2>为什么是 BM25 而不是向量检索</h2>
 * <p>知识库是本地目录里的面试题库/项目资料（.md/.txt，会持续补充），规模在
 * 几百个文件量级。这个量级下 BM25 关键词检索的召回质量足够，而且：
 * <ul>
 *   <li>不依赖 embedding 模型——少一个网络调用、少一份 API 计费、少一种失败模式；</li>
 *   <li>索引纯内存构建，文件变更后秒级重建，天然满足「后续会补充」的热更新诉求；</li>
 *   <li>面试场景的查询（候选人谈到的模块名、技术词）与文档用词高度重合，
 *       正是关键词检索最擅长的分布，向量检索的语义泛化优势在这里用不上多少。</li>
 * </ul>
 * 将来知识库涨到万级文件或需要语义召回时，把本类换成向量实现即可，
 * {@link #retrieve} 的签名保持不变。
 *
 * <h2>中文分词</h2>
 * <p>不引入分词器（jieba/HanLP 都是一份不小的词典依赖），中文按二元组（bigram）切：
 * 「多端互踢」→ 多端/端互/互踢。bigram 对专名检索的效果接近分词，
 * 索引体积约大一倍，在本规模下无所谓。英文与数字按连续词切并转小写。
 *
 * <h2>索引刷新</h2>
 * <p>每次检索前对比目录签名（相对路径+大小+修改时间的拼接串），签名变了才重建；
 * 对比本身受 {@code rescanIntervalSeconds} 限频，文件很多时不至于每轮对话都遍历磁盘。
 * 整个刷新在 synchronized 块里做，并发请求只会触发一次重建。
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    /** BM25 参数，取通用默认值 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** 单个知识块的目标长度；超过 maxChunkChars 的段落硬切 */
    private static final int CHUNK_TARGET_CHARS = 500;
    private static final int CHUNK_MAX_CHARS = 900;

    private final ImAiProperties properties;

    /** 不可变索引快照，整体替换保证读侧无锁 */
    private volatile Index index = Index.EMPTY;
    private volatile long lastCheckAt = 0L;

    public KnowledgeBaseService(ImAiProperties properties) {
        this.properties = properties;
    }

    /**
     * 检索与查询最相关的知识片段。
     *
     * @param query 查询文本（取候选人最后一条消息）
     * @param topK  返回片段数上限
     * @return 按相关度降序的片段列表；知识库为空或无命中时返回空列表
     */
    public List<Chunk> retrieve(String query, int topK) {
        ensureFresh();
        Index snapshot = this.index;
        if (snapshot.chunks.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryTerms = tokenize(query).keySet();
        List<Scored> scored = new ArrayList<>();
        for (Chunk chunk : snapshot.chunks) {
            double score = score(chunk, queryTerms, snapshot);
            if (score > 0) {
                scored.add(new Scored(chunk, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(scored.get(i).chunk());
        }
        return result;
    }

    /** 知识库概况：文件数、片段数、目录是否已配置存在。给前端状态条与提示词组装用 */
    public Map<String, Object> status() {
        ensureFresh();
        Index snapshot = this.index;
        Path dir = Path.of(properties.getKnowledgeDir());
        Map<String, Object> status = new HashMap<>();
        status.put("knowledgeDir", properties.getKnowledgeDir());
        status.put("dirExists", Files.isDirectory(dir));
        status.put("fileCount", snapshot.fileCount);
        status.put("chunkCount", snapshot.chunks.size());
        return status;
    }

    /* ==================== 索引构建与刷新 ==================== */

    private void ensureFresh() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < properties.getRescanIntervalSeconds() * 1000L) {
            return;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - lastCheckAt < properties.getRescanIntervalSeconds() * 1000L) {
                return;
            }
            lastCheckAt = System.currentTimeMillis();
            try {
                String signature = scanSignature();
                if (signature.equals(index.signature)) {
                    return;
                }
                index = buildIndex(signature);
                log.info("AI 面试官知识库索引已重建: dir={}, files={}, chunks={}",
                        properties.getKnowledgeDir(), index.fileCount, index.chunks.size());
            } catch (IOException e) {
                // 目录不存在或不可读不算致命错误：知识库为空时面试照常进行，只是没有检索增强
                log.warn("AI 面试官知识库扫描失败: {}", e.getMessage());
            }
        }
    }

    /** 目录签名：全部文件的「相对路径|大小|修改时间」排序拼接，任何增删改都会改变签名 */
    private String scanSignature() throws IOException {
        List<String> entries = listFiles().stream()
                .map(p -> {
                    try {
                        return p.getFileName() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return p.getFileName() + "|?";
                    }
                })
                .sorted()
                .toList();
        return String.join(";", entries) + "#" + properties.getKnowledgeDir();
    }

    private List<Path> listFiles() throws IOException {
        Path dir = Path.of(properties.getKnowledgeDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .sorted()
                    .toList();
        }
    }

    private Index buildIndex(String signature) throws IOException {
        List<Path> files = listFiles();
        List<Chunk> chunks = new ArrayList<>();
        for (Path file : files) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("知识库文件读取失败，已跳过: {}, {}", file, e.getMessage());
                continue;
            }
            String fileName = file.getFileName().toString();
            for (String text : splitChunks(content)) {
                Map<String, Integer> tf = tokenize(text);
                int length = tf.values().stream().mapToInt(Integer::intValue).sum();
                chunks.add(new Chunk(fileName, text, tf, length));
            }
        }
        // 文档频率：包含某词的块数，BM25 的 idf 用它算
        Map<String, Integer> df = new HashMap<>();
        for (Chunk chunk : chunks) {
            for (String term : chunk.termFreq().keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        double avgLen = chunks.isEmpty() ? 1
                : chunks.stream().mapToInt(Chunk::length).average().orElse(1);
        return new Index(chunks, df, avgLen, files.size(), signature);
    }

    /** 按空行分段再按目标长度归并/硬切，Markdown 标题行强制开新块，保留章节边界 */
    private List<String> splitChunks(String content) {
        String[] paragraphs = content.split("\\r?\\n\\s*\\r?\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : paragraphs) {
            String para = raw.strip();
            if (para.isEmpty()) {
                continue;
            }
            boolean isHeading = para.startsWith("#");
            if (current.length() > 0 && (isHeading || current.length() + para.length() > CHUNK_TARGET_CHARS)) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (para.length() > CHUNK_MAX_CHARS) {
                // 超长段落（表格、代码块）按固定窗口硬切
                for (int i = 0; i < para.length(); i += CHUNK_TARGET_CHARS) {
                    chunks.add(para.substring(i, Math.min(para.length(), i + CHUNK_TARGET_CHARS)));
                }
                continue;
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(para);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /* ==================== 检索打分 ==================== */

    private double score(Chunk chunk, Set<String> queryTerms, Index snapshot) {
        int n = snapshot.chunks.size();
        double score = 0;
        for (String term : queryTerms) {
            int tf = chunk.termFreq().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = snapshot.docFreq.getOrDefault(term, 0);
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            double norm = tf + K1 * (1 - B + B * chunk.length() / snapshot.avgLen);
            score += idf * (tf * (K1 + 1)) / norm;
        }
        return score;
    }

    /**
     * 分词：英文数字连续串转小写为一个词；CJK 连续段切二元组（单字段落保留单字）。
     */
    static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> freq = new HashMap<>();
        String lower = text.toLowerCase();
        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                int start = i;
                while (i < lower.length() && isCjk(lower.charAt(i))) {
                    i++;
                }
                String segment = lower.substring(start, i);
                if (segment.length() == 1) {
                    freq.merge(segment, 1, Integer::sum);
                } else {
                    for (int j = 0; j + 2 <= segment.length(); j++) {
                        freq.merge(segment.substring(j, j + 2), 1, Integer::sum);
                    }
                }
            } else if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < lower.length() && Character.isLetterOrDigit(lower.charAt(i)) && !isCjk(lower.charAt(i))) {
                    i++;
                }
                freq.merge(lower.substring(start, i), 1, Integer::sum);
            } else {
                i++;
            }
        }
        return freq;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /* ==================== 内部结构 ==================== */

    /** 一个知识片段：来源文件名 + 原文 + 词频表（检索打分用） */
    public record Chunk(String fileName, String text, Map<String, Integer> termFreq, int length) {
    }

    private record Scored(Chunk chunk, double score) {
    }

    private record Index(List<Chunk> chunks, Map<String, Integer> docFreq, double avgLen,
                         int fileCount, String signature) {
        static final Index EMPTY = new Index(List.of(), Map.of(), 1, 0, "");
    }
}
