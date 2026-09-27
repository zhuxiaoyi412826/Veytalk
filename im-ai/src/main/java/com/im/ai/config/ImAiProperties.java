package com.im.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 面试官的项目侧配置，挂在 {@code im.ai} 前缀下。
 *
 * <p>模型接入参数（地址/密钥/模型名）在 {@link AiProperties}，
 * 这里只放知识库与对话策略：两者的变更频率和责任人不同。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im.ai")
public class ImAiProperties {

    /** 总开关。关闭后面试接口直接报「AI 面试官未启用」，不再触碰模型与知识库 */
    private boolean enabled = true;

    /** 知识库目录，递归扫描其中的 .md / .txt 文件；目录内容变更后自动重建索引 */
    private String knowledgeDir = "D:/资料/知识库/面试官";

    /** 每轮对话注入提示词的知识片段条数 */
    private int retrieveTopK = 4;

    /** 发送给模型的历史消息条数上限（不含 system），防止长面试把上下文撑爆 */
    private int maxHistory = 30;

    /** 单条消息内容长度上限，防御性截断 */
    private int maxMessageChars = 4000;

    /** 注入提示词的知识片段总字符数上限 */
    private int maxKnowledgeChars = 6000;

    /**
     * 知识库目录重扫的最小间隔（秒）。
     * 每次面试请求都会对比目录签名（文件路径+大小+修改时间）来决定是否重建索引，
     * 这个间隔限制的是「对比」本身的频率，目录里文件很多时避免每轮对话都走一遍文件系统。
     */
    private long rescanIntervalSeconds = 30;

    /** 全网检索：消息搜索框「网络」分组的数据源，与面试功能互不影响 */
    private WebSearch webSearch = new WebSearch();

    /**
     * 全网检索配置。
     *
     * <p>抓取入口做成可配置：结果页 HTML 结构属于外部站点，不受本项目控制，
     * 换入口（或将来换成付费搜索 API）时只改配置不动代码。
     */
    @Data
    public static class WebSearch {

        /** 总开关。关闭后接口直接返回空结果，前端连「网络」分隔线都不渲染 */
        private boolean enabled = true;

        /** 抓取入口地址（国内网络可直连且返回标准结果页的是 Bing） */
        private String endpoint = "https://cn.bing.com/search";

        /**
         * 单次抓取超时（秒）。
         * 搜索框是即时交互，网络分组慢就等于没有——超时后宁可什么都不显示，
         * 也不能让请求线程挂在一次跨网抓取上。
         */
        private int timeoutSeconds = 6;

        /** 返回给前端的条数上限 */
        private int maxResults = 10;

        /** 关键字长度上限，超出截断（关键字会拼进抓取 URL，也参与缓存键计算） */
        private int maxKeywordChars = 60;

        /** 摘要片段截断长度，抓取页里的原始摘要可能很长 */
        private int maxSnippetChars = 200;

        /**
         * 结果缓存有效期（秒）。同一关键字反复搜不必每次都打外网，
         * 也让多用户搜同一个热词时只有一次真实抓取。
         */
        private long cacheSeconds = 600;
    }
}
