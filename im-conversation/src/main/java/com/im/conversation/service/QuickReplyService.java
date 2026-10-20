package com.im.conversation.service;

import com.im.common.api.ResultCode;
import com.im.common.constant.RedisKeys;
import com.im.common.exception.BusinessException;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 个人常用语（快捷回复）：聊天输入框旁边那份「点一下就填进去」的话术清单。
 *
 * <h2>为什么只存 Redis、不建表</h2>
 *
 * <p>与弹幕屏蔽词同一判断：低频写、小体量、只有本人维护。零 DDL 的代价也照旧写明白——
 * <b>Redis 是唯一存储</b>，键按 {@link #RETENTION_DAYS} 天滑动续期，一次 Redis 数据丢失或
 * 用户一年不再改动，这份清单就没了。要长期可靠必须落表，那不在本批范围。
 *
 * <p>为什么不学「界面偏好」那样只放 localStorage：那些是纯外观，跨不同步无所谓；常用语是
 * 拿来<b>发消息</b>的，手机浏览器上没有桌面端攒下的话术，功能等于直接消失。
 *
 * <h2>为什么是整表替换</h2>
 *
 * <p>清单只有二十来条，前端本来就一次性渲染全部；逐条增删改要多一套插入语义换来的一致性
 * 收益几乎为零，还会把「顺序」这个用户能直接看见的属性搞复杂。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickReplyService {

    /** 条数上限：常用语是「随手能点到的几句」，不是笔记本。再多面板要滚动，反而没人用 */
    private static final int MAX_ITEMS = 20;

    /** 单条最大长度：消息本身有更长上限，这里取「一句话」的量级，超出直接拒 */
    private static final int MAX_ITEM_LENGTH = 200;

    /**
     * 滑动保留期（天）：每次写入重设。这是这份清单的实际寿命——没有别的地方存着它。
     */
    private static final int RETENTION_DAYS = 365;

    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;

    /** 本人清单，顺序即当初写入的顺序；没配过返回空列表 */
    public List<String> list(Long userId) {
        if (userId == null) {
            return List.of();
        }
        String raw = redisUtil.get(RedisKeys.quickReply(userId));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // toList 对坏值返回空列表：常用语读不出来不该让聊天窗口报错，顶多是清单显示为空
        List<String> items = jsonUtil.toList(raw, String.class);
        return items == null ? List.of() : items;
    }

    /**
     * 整表替换。清洗规则：去首尾空白、丢空串、去重（保留首次出现的顺序）。
     *
     * <p>超限与超长都直接报错而<b>不静默截断</b>：截断会让用户以为自己存下了第 21 条，
     * 等到真要用的时候才发现没有。
     *
     * @return 清洗后实际生效的清单；前端拿它覆盖本地状态，界面上看到的就是存下来的
     */
    public List<String> replace(Long userId, List<String> rawReplies) {
        BusinessException.throwIf(userId == null, ResultCode.SYSTEM_ERROR);
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : rawReplies == null ? List.<String>of() : rawReplies) {
            String text = raw == null ? "" : raw.trim();
            if (text.isEmpty()) {
                continue;
            }
            BusinessException.throwIf(text.length() > MAX_ITEM_LENGTH, ResultCode.BAD_REQUEST,
                    "单条常用语最多 " + MAX_ITEM_LENGTH + " 字");
            if (seen.add(text)) {
                cleaned.add(text);
            }
        }
        BusinessException.throwIf(cleaned.size() > MAX_ITEMS, ResultCode.BAD_REQUEST,
                "常用语最多 " + MAX_ITEMS + " 条");

        String key = RedisKeys.quickReply(userId);
        if (cleaned.isEmpty()) {
            // 清空就删键，不留一个内容为 "[]" 的键占内存
            redisUtil.delete(key);
            log.info("常用语清空: userId={}", userId);
            return cleaned;
        }
        String json = jsonUtil.toJson(cleaned);
        BusinessException.throwIf(json == null, ResultCode.SYSTEM_ERROR);
        redisUtil.set(key, json, Duration.ofDays(RETENTION_DAYS));
        log.info("常用语更新: userId={}, 生效 {} 条", userId, cleaned.size());
        return cleaned;
    }
}
