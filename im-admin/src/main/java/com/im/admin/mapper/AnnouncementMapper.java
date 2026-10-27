package com.im.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.admin.entity.Announcement;

/**
 * 全服公告 Mapper。
 *
 * <p>后台分页（按状态 + 发布/创建时间倒序）与用户端补拉（状态=已发布、未过期、倒序取前几条）
 * 都能用 LambdaQueryWrapper 拼出来，{@code idx_status_publish} 正好各对上一条，
 * 所以这里没有自定义 SQL。
 */
public interface AnnouncementMapper extends BaseMapper<Announcement> {
}
