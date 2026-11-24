package com.im.admin.service;

import cn.dev33.satoken.stp.StpUtil;
import com.im.common.api.ResultCode;
import com.im.common.enums.WorkBoardType;
import com.im.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 通用作品接口的<b>动态</b>权限闸门：把 URL 里的 {@code {type}} 映射成对应板块的权限点并校验。
 *
 * <p>为什么不用 {@code @SaCheckPermission}：那个注解的值是编译期常量，而这里要校验的权限点
 * 取决于请求路径上的一段变量。三个板块写三个同构控制器确实能用注解解决问题，但代价是三份
 * 一模一样的翻页/下架/删除代码——将来加一个筛选项要改三遍，漏改的那两屏会静默地行为不一致。
 *
 * <p>退化成注解的<b>反面</b>方案更不能接受：类上只挂 {@code system:manage} 伞权限，或挂
 * {@code @SaCheckLogin} 就完事。Sa-Token 的权限匹配是<b>字符串精确匹配</b>（只有 {@code *} 才是
 * 通配），所以 {@code system:manage} 既通配不到 {@code system:image:manage}，挂登录校验则等于
 * 任何管理员都能改任何板块。「一屏一个权限点」这条约定靠本类守住，而不是靠控制器数量。
 *
 * <p>抛出的 {@code NotPermissionException} 由 {@code GlobalExceptionHandler} 映射成 1003，
 * 前端表现与注解式校验完全一致——本类不改变错误码，只改变「校验哪一个权限点」的决定时机。
 */
@Component
public class WorkAuthGuard {

    /**
     * 校验当前管理员对 {@code type} 这一屏是否有管理权限。
     *
     * <p>顺序是刻意的：<b>先判板块合法性再判权限</b>。未知板块回 400（参数问题），
     * 已知板块但无权限回 1003（权限问题）——把两者混成一个 1003，会让「前端把 URL 拼错了」
     * 这种自伤看起来像被攻击，排查时白绕一圈。
     *
     * <p>{@code video} 是已知板块但不走通用接口（它有 {@code /api/admin/video/**} 一整套端点），
     * 这里同样拒绝，避免两套后台入口改同一批行却按不同的校验路径。
     *
     * @return 规范化后的板块枚举，调用方可直接用 {@code getCode()} 覆盖请求里原始的大小写/空白
     */
    public WorkBoardType check(String type) {
        WorkBoardType board = WorkBoardType.ofAdminWork(type);
        if (board == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    WorkBoardType.of(type) == WorkBoardType.VIDEO
                            ? "视频板块请走 /api/admin/video/**" : "未知的板块：" + type);
        }
        StpUtil.checkPermission(board.getPermCode());
        return board;
    }
}
