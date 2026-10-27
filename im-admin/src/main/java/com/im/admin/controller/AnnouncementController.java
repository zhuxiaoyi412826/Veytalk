package com.im.admin.controller;

import com.im.admin.dto.vo.AnnouncementVO;
import com.im.admin.service.AnnouncementService;
import com.im.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户侧的公告补拉（四期第四批）。
 *
 * <p><b>为什么用户能读的公告，代码却放在 im-admin</b>：这张表的归属模块就是 im-admin
 * （和一期把用户侧提交举报的 {@code ReportController} 放在这里完全同一先例）。
 * 把它挪到某个业务模块去，就得为这一个查询在 {@code com.im.common.spi} 上多开一条契约，
 * 而那条契约永远只有一个实现方在用——星型架构管的是跨模块取数，不是把一个模块自己的表拆成两半。
 *
 * <p>权限边界靠路径划开：本类映射 {@code /api/announcement/**}，类上<b>没有任何权限注解</b>，
 * 只要登录就能读（全局拦截器保证登录）；后台那五个写动作在
 * {@code /api/admin/announcement/**} 并要求 {@code system:announcement}。
 *
 * <p>这一条是离线用户的<b>唯一</b>补偿通道：广播只覆盖发布那一刻在线的连接，错过的人靠这里补。
 * 前端在登录成功后与 WebSocket 重连后各拉一次，并按 ID 去重（本地已读集合），
 * 所以「同一台设备不会重复弹同一条」，而换设备会重看一遍——没有已读回执表，
 * 那是这条链路上唯一被明确放弃的精确性。
 */
@Tag(name = "17-公告", description = "当前生效的全服公告（补拉通道）")
@RestController
@RequestMapping("/api/announcement")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "当前生效的公告",
            description = "已发布且未过期的最新几条，按发布时刻倒序；草稿与已撤回的一律不返回。"
                    + "前端按 ID 去重，重复拉取没有副作用，所以这里不加限流")
    @GetMapping("/active")
    public Result<List<AnnouncementVO>> active() {
        return Result.ok(announcementService.activeForUser());
    }
}
