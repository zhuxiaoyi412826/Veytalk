package com.im.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.file.entity.ModerationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图片审核命中流水 Mapper（五期·路线 2）。
 *
 * <p>只有一种取数形状：后台按维度过滤 + 主键倒序翻页（在 {@code ModerationAdminSpiImpl}
 * 里用 wrapper 拼），没有自定义 SQL。流水表不做 UPDATE / DELETE——连逻辑删除都没有，
 * 销毁追责现场的口子从代码侧也不留。
 */
@Mapper
public interface ModerationLogMapper extends BaseMapper<ModerationLog> {
}
