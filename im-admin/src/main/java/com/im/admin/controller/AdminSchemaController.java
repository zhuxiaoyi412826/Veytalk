package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.vo.SchemaLogVO;
import com.im.admin.dto.vo.SchemaOverviewVO;
import com.im.admin.dto.vo.SchemaScriptVO;
import com.im.admin.dto.vo.SchemaVersionVO;
import com.im.admin.service.SchemaMigrationService;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台的 SQL 版本管理（台账 1.0.5）。
 *
 * <p>这是全后台唯一「按钮直接改库结构」的端点族，权限点 {@code system:schema:manage}
 * 的种子刻意只授 admin 角色。应用/回滚都必须由登录人点击触发（MySQL 写由本人执行的团队
 * 约定在这里的形态就是：机器不动手，人点按钮，逐条落 {@code im_schema_migration_log} 流水）。
 *
 * <p>动作端点用 POST 而不是 PUT/DELETE：apply/rollback 都是「发起一次变更」的命令，
 * 不幂等于同一个目标态（第二次 apply 会被状态机拒绝），语义上就是动词 + 版本号。
 */
@Tag(name = "16-管理后台", description = "SQL 版本管理：台账可视化、脚本应用与一键回滚")
@RestController
@RequestMapping("/api/admin/schema")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_SCHEMA_MANAGE)
public class AdminSchemaController {

    private final SchemaMigrationService schemaMigrationService;

    @Operation(summary = "版本清单",
            description = "im_schema_migration 台账行与 sql/versions 目录合并视图（升序）；"
                    + "含漂移检测（checksum 不一致标红）与 canApply/canRollback 按钮位；"
                    + "引导表没建时 tableReady=false 空清单，不报 500")
    @GetMapping("/overview")
    public Result<SchemaOverviewVO> overview() {
        return Result.ok(schemaMigrationService.overview());
    }

    @Operation(summary = "变更流水",
            description = "谁、何时、对哪个版本做了 APPLY/ROLLBACK、结果与耗时（时间线数据源，倒序）")
    @GetMapping("/logs")
    public Result<List<SchemaLogVO>> logs(
            @Parameter(description = "条数上限 1~200，默认 100") @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(schemaMigrationService.logs(limit == null ? 100 : limit));
    }

    @Operation(summary = "查看脚本原文",
            description = "按版本号返回 sql/versions 下 V/U 脚本全文（只读；文件名由服务端目录扫描解析，不接受文件名入参）；"
                    + "历史基线等无实体文件的版本返回 404")
    @GetMapping("/script")
    public Result<SchemaScriptVO> script(
            @Parameter(description = "版本号，如 1.0.4") @RequestParam("version") String version,
            @Parameter(description = "true=回滚脚本 U，false/缺省=向前脚本 V") @RequestParam(value = "undo", required = false, defaultValue = "false") boolean undo) {
        return Result.ok(schemaMigrationService.script(version.trim(), undo));
    }

    @Operation(summary = "应用版本",
            description = "逐条执行 V<版本>__*.sql；须按版本号升序、半途失败记 FAILED 台账并要求人工修补后重放；"
                    + "DDL 不可回卷，执行前先备份是页面前置确认弹框的职责")
    @PostMapping("/apply")
    public Result<SchemaVersionVO> apply(@Parameter(description = "版本号，如 1.0.6") @RequestParam("version") String version) {
        return Result.ok(schemaMigrationService.apply(version.trim(), SecurityUtil.getUserId()),
                "版本 " + version + " 已应用");
    }

    @Operation(summary = "一键回滚",
            description = "执行该版本的 U 脚本；仅允许回滚最近一次已应用版本（LIFO），基线行无 U 脚本不可回滚；"
                    + "结构可还原、数据不可恢复，执行前先备份")
    @PostMapping("/rollback")
    public Result<SchemaVersionVO> rollback(@Parameter(description = "版本号") @RequestParam("version") String version) {
        return Result.ok(schemaMigrationService.rollback(version.trim(), SecurityUtil.getUserId()),
                "版本 " + version + " 已回滚");
    }
}
