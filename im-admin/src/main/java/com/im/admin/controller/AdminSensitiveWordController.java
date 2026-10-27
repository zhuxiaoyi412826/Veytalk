package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.SensitiveWordAddRequest;
import com.im.admin.dto.vo.SensitiveWordImportVO;
import com.im.admin.dto.vo.SensitiveWordVO;
import com.im.admin.service.SensitiveWordService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.PageQuery;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台在线维护敏感词（四期第四批）。
 *
 * <p>权限沿用 {@code system:config}：加一个词与改一个开关是同一类动作——都在调全服内容过滤的行为，
 * 影响面也都是「之后的消息」，没有哪一边更大，所以不另开权限点。
 *
 * <p><b>在线词库是文件词库之上的增量，不是替代</b>：这里删掉一行只撤掉后台加的那一个词，
 * classpath 里那 19643 条一条都不会少；反过来想屏蔽或摘掉文件里的某个词，本页面做不到，
 * 那要改词库资源重新打包。这条边界写在 {@code SensitiveWord} 实体注释与开发指南里，
 * 而不是等到有人删了三行发现「还在遮」再来问。
 *
 * <p>改动即时生效于本节点（service 里当场重建字典树），其他节点最迟 30 秒后收敛。
 */
@Tag(name = "16-管理后台", description = "系统配置：在线敏感词库")
@RestController
@RequestMapping("/api/admin/sensitive-word")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_CONFIG)
public class AdminSensitiveWordController {

    private final SensitiveWordService sensitiveWordService;

    @Operation(summary = "词库分页",
            description = "只列后台加的词，不含 classpath 文件词库那一份；关键词按折叠后的形式匹配")
    @GetMapping("/page")
    public Result<PageResult<SensitiveWordVO>> page(PageQuery query) {
        return Result.ok(sensitiveWordService.page(query));
    }

    @Operation(summary = "新增词条",
            description = "单个词或按顿号/逗号/换行分隔的一批词；返回拆出多少、入库多少、重复多少、非法多少，"
                    + "并带回重建后的生效总数。词条至少 2 个字符且只含汉字与小写字母数字")
    @PostMapping
    public Result<SensitiveWordImportVO> add(@RequestBody @Valid SensitiveWordAddRequest request) {
        return Result.ok(sensitiveWordService.add(request.getWord(), request.getRemark(), SecurityUtil.getUserId()),
                "词库已更新，本节点立即生效");
    }

    @Operation(summary = "删除词条",
            description = "按 ID 删而不是按词删；删掉的是后台加的那一行，文件词库里的词不受影响（也不在这里显示）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "词条 ID") @PathVariable("id") Long id) {
        sensitiveWordService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "已删除，本节点立即生效");
    }
}
