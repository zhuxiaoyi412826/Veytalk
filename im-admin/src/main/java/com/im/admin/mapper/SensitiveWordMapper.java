package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.SensitiveWord;
import org.apache.ibatis.annotations.Select;

/**
 * 后台敏感词 Mapper。
 *
 * <p>分页、按词查、删除都走 {@code BaseMapper} 的通用方法；这里只多一条聚合，
 * 给定时同步任务当「词表有没有变过」的签名用。
 */
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

    /**
     * 词表签名：{@code 条数|最后更新时间}，表为空时形如 {@code 0|}。
     *
     * <p>为什么要有它：重建一次字典树是几十毫秒级的 CPU 开销（文件词库近两万条），
     * 而词表一天里可能一次都不变。定时任务每 30 秒跑一次，如果每轮都无脑重建，
     * 就等于用一个没人管的后台功能给全服消息链路常驻加一笔开销。
     * 拿签名比对的代价是一条走不了索引的全表聚合，但它扫的是几十行，
     * 比「取回全部词条再拼哈希」便宜得多。
     *
     * <p>为什么不用 {@code MAX(id)} 当签名：它只认新增。删掉最大那一行它会退回次大值，
     * 而删掉中间任意一行时它根本不变——后者才是常态，那就会变成「后台把词删了、
     * 定时任务却认为什么都没发生」，那个词继续遮着用户的消息且无人知道。
     * 条数与最后更新时间两维合起来才把增、删都盖住（删除一定改条数）。
     */
    @Select("SELECT CONCAT(COUNT(*), '|', IFNULL(MAX(update_time), '')) FROM im_sensitive_word")
    String selectSignature();
}
