package com.im.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.ai.dto.vo.WebSearchVO;
import com.im.ai.service.WebSearchService;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全网检索接口：消息搜索框「网络」分组的数据源。
 *
 * <p>与聊天消息检索（{@code GET /api/message/search}）是两个独立请求，前端并行发起：
 * 本地结果一定要立刻出来，网络结果受外网抖动影响可能慢几秒，
 * 合成一个接口返回就等于让本地结果陪着外网一起等。
 *
 * <p>失败不报错：抓取超时、被搜索引擎限流、结果页改版，一律返回空 results，
 * 前端只是不渲染「网络」这一栏。
 *
 * <p>限流按用户 20 次/分钟：前端已经用防抖收敛过输入频率，这个数字要留够正常改关键字的余量，
 * 又能挡住拿搜索框做外网洪水脚本的行为（未命中缓存的每一次都是一次真实跨网抓取）。
 */
@Tag(name = "10-全网搜索", description = "消息搜索框的网络分组：互联网关键字检索")
@RestController
@RequestMapping("/api/ai/search")
@RequiredArgsConstructor
@SaCheckLogin
public class WebSearchController {

    private final WebSearchService webSearchService;

    @Operation(summary = "全网关键字检索",
            description = "返回网页标题、地址、来源站点与摘要；results 为空表示没有网络结果（含抓取失败），不视为错误")
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "ai.web.search")
    @GetMapping("/web")
    public Result<WebSearchVO> web(@RequestParam String keyword) {
        return Result.ok(webSearchService.search(keyword));
    }
}
