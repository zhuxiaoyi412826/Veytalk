package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.LoginLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 登录流水 Mapper。
 *
 * <p>查询一律走 {@code BaseMapper} + LambdaQuery（按 user_id 过滤、按主键倒序，雪花 ID 即时间序，
 * 与 {@code im_moderation_log} 同一口径）。只有保留期清理需要一条手写 SQL：
 * 它必须带 {@code LIMIT}，而逻辑删除风格的 wrapper 拼不出这个尾巴。
 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {

    /**
     * 按保留期分批物理删除。
     *
     * <p>带 {@code LIMIT} 而不是一条 {@code DELETE WHERE create_time < ?} 删干净：
     * 一张长期没清过的流水表可能有几十万行，一条语句删完会长时间持有大量行锁与 undo，
     * 而这是一个后台清理任务，慢一点、多跑几轮完全没关系。
     *
     * @return 本次删除的行数；等于 {@code limit} 时说明还有存货，下个周期接着删
     */
    @Delete("DELETE FROM im_login_log WHERE create_time < #{deadline} LIMIT #{limit}")
    int deleteExpired(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);
}
