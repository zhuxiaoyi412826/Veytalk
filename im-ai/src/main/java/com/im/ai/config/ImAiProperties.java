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

    /** 监考：切屏/粘贴的检测与处置策略 */
    private Proctor proctor = new Proctor();

    /**
     * 监考配置。
     *
     * <p>「哪些动作算违规」是业务判断，不能由前端定：前端只负责报告「发生了什么」，
     * violation 标志与是否入计数全部在服务端按这里的开关算——否则改一下 js 就能把
     * 粘贴刷成不算违规。下面的开关因此都只影响服务端的判定，前端拿到的只是结果。
     *
     * <p>{@code violationLimit} 是唯一的「拦截」配置：达到后服务端直接把会话置为强制结束，
     * 之后该 sessionId 的所有上报都报错。只警告不结束是没有用的——候选人会忽略提示继续。
     */
    @Data
    public static class Proctor {

        /** 总开关。关闭后不建会话、不收事件，面试回到无状态行为 */
        private boolean enabled = true;

        /**
         * 违规次数上限：累计达到这个数就强制结束面试。
         * 0 或负数表示不封顶（只留痕不中断），给「纯练习模式」留的口子。
         */
        private int violationLimit = 8;

        /**
         * 达到上限是否真的强制结束。关掉后只做记录并在响应里回传已达阈值，
         * 用于「先观察阈值定得合不合适」的阶段，不必为此改代码。
         */
        private boolean enforceLimit = true;

        /** 窗口失焦（blur）是否计违规 */
        private boolean countBlur = true;

        /** 标签页/最小化（visibility-hidden）是否计违规 */
        private boolean countVisibilityHidden = true;

        /** 复制是否计违规（只复制自己写的答不算作弊，故默认不计） */
        private boolean countCopy = false;

        /** 剪切同复制 */
        private boolean countCut = false;

        /** 粘贴是否计违规 */
        private boolean countPaste = true;

        /** 右键菜单是否计违规 */
        private boolean countContextmenu = false;

        /** 退出全屏是否计违规 */
        private boolean countFullscreenExit = true;

        /** 单次批量上报的事件条数上限，防前端/脚本滥用 */
        private int maxEventsPerBatch = 100;

        /** 事件 detail 字段落库前的截断长度，与表列宽一致 */
        private int maxDetailChars = 500;
    }

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
