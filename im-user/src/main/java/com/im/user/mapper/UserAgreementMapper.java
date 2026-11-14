package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.UserAgreement;

/**
 * 同意留痕 Mapper，由启动类的 {@code @MapperScan("com.im.**.mapper")} 自动注册。
 *
 * <p>只写不读的路径靠 BaseMapper 足够：插入一条留痕、按「用户 + 版本」查有没有同意过、
 * 按用户查完整同意链、后台多条件分页，四种全是单表查询。
 *
 * <p>这张表<b>没有任何 UPDATE / DELETE 的调用方</b>：留痕一旦写下就是历史事实，
 * 改它比删它更糟（删了是销毁证据，改了是伪造证据）。mapper 层拦不住这种调用，
 * 真正的护栏是上层没有暴露任何写这两个动作的入口。
 */
public interface UserAgreementMapper extends BaseMapper<UserAgreement> {
}
