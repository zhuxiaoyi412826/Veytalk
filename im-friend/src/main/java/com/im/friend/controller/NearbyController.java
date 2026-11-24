package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.vo.NearbyPeerVO;
import com.im.friend.service.NearbyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「附近的人」接口，路径 {@code /api/dating/nearby} 落在 Knife4j 的「20-交友」分组里
 * （分组按路径前缀切，新增子路径不需要改 {@code Knife4jConfig}）。
 *
 * <p><b>只有一个读端点</b>：喜欢仍然走 {@code POST /api/dating/match/action} 传 {@code source=2}，
 * 更新自己的定位仍然走交友资料保存（{@code PUT /api/dating/profile}）。这里再开一条写路径
 * 就会多出第二套成对逻辑，而「附近的人」与卡池本来就是同一个候选池的两种读法。
 *
 * <p>只要登录，不要额外权限码：这一屏只返回按查看者视角收口过的公开卡片，且看不到任何精确坐标；
 * 限流 60 次/分钟是防拿它当坐标探测仪高频刷（每次请求都会按查看者当前位置重算一遍距离序）。
 */
@Tag(name = "20-交友", description = "附近的人：按距离由近到远，只给模糊距离文案")
@Validated
@RestController
@RequestMapping("/api/dating/nearby")
@RequiredArgsConstructor
@SaCheckLogin
public class NearbyController {

    private final NearbyService nearbyService;

    @Operation(summary = "附近的人",
            description = "按距你由近到远返回；距离只给模糊文案（500 m 内 / 1 km 内 / 取整公里），不给坐标。"
                    + "你自己还没定位或定位超过 7 天失效时返回 2042，前端引导去交友资料里重新定位")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.nearby.list")
    @GetMapping
    public Result<List<NearbyPeerVO>> nearby(
            @Parameter(description = "半径（米）500-20000，默认 3000；越界直接被参数校验拦下，服务端还会再夹一次")
            @RequestParam(required = false) @Min(value = 500, message = "半径最小 500 米")
            @Max(value = 20000, message = "半径最大 20000 米，更大请用同城筛选") Integer radius,
            @Parameter(description = "期望条数 1-50，默认 20")
            @RequestParam(required = false) @Min(value = 1, message = "size 最小为 1")
            @Max(value = 50, message = "size 最大为 50") Integer size) {
        return Result.ok(nearbyService.nearby(SecurityUtil.getUserId(), radius, size));
    }
}
