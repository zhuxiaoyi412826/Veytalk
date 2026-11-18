package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * 意见反馈 Mapper：全是单表操作，无需自定义 SQL。
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
