package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.vo.ConfigItemVO;
import com.im.admin.dto.vo.SensitiveFilterVO;
import com.im.admin.service.AdminConfigService;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台的系统配置页（四期）。四期第一批只搬了敏感词开关那一对端点，第四批又把下面
 * 三个 {@code /item} 端点加在同一类上：整份可改目录由 {@code AdminConfigKey} 白名单给出，
 * 加一个旋钮就是往枚举里添一项，不再为某个开关单开一个端点。
 *
 * <p><b>这两个端点是从用户侧搬过来的，不是新增</b>：原先它们是
 * {@code GET/PUT /api/message/sensitive-filter}，类上只有 {@code @SaCheckLogin}，
 * 而全局拦截器只做登录校验、权限判定完全依赖注解——等于任何一个登录用户都能
 * 一句话把<b>全服</b>敏感词遮罩关掉（聊天端「设置 › 高级设置」里那个 switch 就是这么用的，
 * 它把一个全服开关摆在了个人设置页上）。现在路径前缀即权限边界：
 * 用户侧端点已删，这里要求 {@code system:config}。
 *
 * <p>用 PUT 而不是 POST：这是对一个已存在的单例状态（全服过滤开关）赋值，
 * 重复提交同一个值结果相同，天然幂等，不需要 POST 那道「创建」的语义。
 */
@Tag(name = "16-管理后台", description = "系统配置：运行期配置目录与敏感词全局开关")
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_CONFIG)
public class AdminConfigController {

    private final AdminConfigService adminConfigService;

    @Operation(summary = "敏感词过滤状态",
            description = "返回运行期内存开关、词库是否装载成功、生效词条数；开关初始值来自配置 im.message.sensitive-filter-enabled")
    @GetMapping("/sensitive-filter")
    public Result<SensitiveFilterVO> sensitiveFilter() {
        return Result.ok(adminConfigService.sensitiveFilter());
    }

    @Operation(summary = "切换敏感词过滤开关",
            description = "热切换内存开关并落库，全服立即生效；重启后由库内那一行盖回来（不再是配置文件的值）。日志里记下操作人")
    @PutMapping("/sensitive-filter")
    public Result<SensitiveFilterVO> toggleSensitiveFilter(
            @Parameter(description = "true 开启过滤，false 关闭") @RequestParam("enabled") Boolean enabled) {
        return Result.ok(adminConfigService.setSensitiveFilter(enabled, SecurityUtil.getUserId()));
    }

    /* ==================== 四期第四批：运行期配置目录 ==================== */

    @Operation(summary = "可改的配置项全量",
            description = "目录（叫什么、属于哪一组、能填多少、改了的后果）全部来自服务端的白名单枚举，"
                    + "前端不自己维护一份；每项带回当前值、启动默认值与是否已被覆盖")
    @GetMapping("/item")
    public Result<List<ConfigItemVO>> items() {
        return Result.ok(adminConfigService.configItems());
    }

    @Operation(summary = "修改一个配置项",
            description = "值一律以字符串传（{@code true} / {@code 120}）：先按白名单校 key，再校类型与取值范围，"
                    + "两者都过才落库、才改运行态。不在白名单里的 key 回 14010，超范围回 400。"
                    + "本节点立即生效，其他节点最迟 30 秒后收敛")
    @PutMapping("/item")
    public Result<ConfigItemVO> updateItem(
            @Parameter(description = "配置键，必须是 /item 返回列表里的某一项") @RequestParam("key") String key,
            @Parameter(description = "新值（字符串形式）") @RequestParam("value") String value) {
        return Result.ok(adminConfigService.updateConfigItem(key, value, SecurityUtil.getUserId()), "已生效");
    }

    @Operation(summary = "恢复默认",
            description = "物理删掉 im_config 里那一行并把运行态写回启动默认值，此后这一项重新跟随配置文件。"
                    + "是删行而不是把值写成默认值：留下那一行，「改配置文件就能跟着变」的语义就永久丢了，"
                    + "而界面上看不出差别")
    @DeleteMapping("/item")
    public Result<ConfigItemVO> resetItem(
            @Parameter(description = "配置键") @RequestParam("key") String key) {
        return Result.ok(adminConfigService.resetConfigItem(key, SecurityUtil.getUserId()), "已恢复启动默认值");
    }
}
