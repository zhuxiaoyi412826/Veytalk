package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.Agreement;

/**
 * 协议文本版本 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>与 {@code UserMapper} 一样不写自定义 SQL：本表的查询形状只有「按 code 取当前生效那一行」
 * 「按 code + version 取历史某一行」「按 code 取最大 version」「后台全状态分页」四种，
 * 全部是单表条件查询，服务层用 LambdaQueryWrapper 拼即可。
 *
 * <p>特别注意本表<b>没有 deleted 列</b>（见 {@link Agreement} 类注释），所以它不能套用
 * 任何「加个 deleted=0」的通用写法，MyBatis-Plus 也不会替它拼——实体没标
 * {@code @TableLogic}，逻辑删除条件根本不生成。
 */
public interface AgreementMapper extends BaseMapper<Agreement> {
}
