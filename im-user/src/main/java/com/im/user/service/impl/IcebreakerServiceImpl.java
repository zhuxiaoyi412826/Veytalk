package com.im.user.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.enums.IcebreakerType;
import com.im.common.exception.BusinessException;
import com.im.user.dto.vo.IcebreakerItemVO;
import com.im.user.dto.vo.IcebreakerSuggestVO;
import com.im.user.entity.Icebreaker;
import com.im.user.entity.InterestTag;
import com.im.user.entity.User;
import com.im.user.entity.UserProfile;
import com.im.user.entity.UserTag;
import com.im.user.mapper.IcebreakerMapper;
import com.im.user.mapper.InterestTagMapper;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserTagMapper;
import com.im.user.service.DatingProfileService;
import com.im.user.service.IcebreakerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 破冰建议实现。
 *
 * <p>一次建议最多四条 SQL：取对方的账号行、取对方的资料行（拿城市）、取两人的标签绑定、
 * 取文案池。<b>不给建议加缓存</b>：它的输入里有一项是「双方共有的标签」，
 * 而任一方随时可能改自己的兴趣，缓存住就等于把过期标签写进开场白——
 * 「你也练健身？」发给一个刚取消健身标签的人，比不给建议更糟。
 *
 * <p>与 {@code DatingTagServiceImpl} 之间没有依赖：共同标签在本类里现算
 * （一次 {@code selectByUserIds} 加一次集合求交），而不是去调标签服务——
 * 标签服务给的是「某个人的标签名列表」，这里要的是「两个人都有的那几个 ID」，
 * 让它多开一个方法只为省两行集合运算，会把「谁负责求交」这件事变成两个模块各说一半。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IcebreakerServiceImpl implements IcebreakerService {

    private final IcebreakerMapper icebreakerMapper;
    private final UserTagMapper userTagMapper;
    private final InterestTagMapper interestTagMapper;
    private final UserMapper userMapper;
    private final DatingProfileService datingProfileService;

    @Override
    public IcebreakerSuggestVO suggest(Long currentUserId, Long targetUserId) {
        // 给自己看破冰没有意义：模板里的 {nickname} 会渲染成自己的昵称，读起来像自言自语
        Long target = targetUserId == null || targetUserId.equals(currentUserId) ? null : targetUserId;
        User peer = target == null ? null : userMapper.selectById(target);
        UserProfile peerProfile = target == null ? null : datingProfileService.find(target);
        Map<Long, String> shared = sharedTags(currentUserId, target);

        List<IcebreakerItemVO> greetings = new ArrayList<>();
        List<IcebreakerItemVO> topics = new ArrayList<>();
        List<IcebreakerItemVO> tagOpeners = new ArrayList<>();
        for (Icebreaker row : icebreakerMapper.selectSuggestPool(shared.keySet())) {
            IcebreakerType type = IcebreakerType.of(row.getType());
            if (type == null) {
                // 库里被塞了一个本枚举不认识的类型：跳过而不是当通用文案下发，
                // 否则「后台加了一档还没做前端」会变成用户看到一句莫名其妙的话
                continue;
            }
            String tagName = row.boundToTag() ? shared.get(row.getTagId()) : null;
            if (row.boundToTag() && tagName == null) {
                // 池子本来就是按共有标签查的，走到这里只有一种可能：这一行的标签已被后台删除
                // 而 shared 只收录字典里还存在的标签。不能把它当通用文案发出去
                continue;
            }
            String content = row.render(peer == null ? null : peer.getNickname(),
                    peerProfile == null ? null : peerProfile.getCity(), tagName);
            if (content == null) {
                // 模板引用的那一项对方没填（例如没写城市），或带了个没人认得的占位符
                continue;
            }
            List<IcebreakerItemVO> bucket = bucketOf(type, greetings, topics, tagOpeners);
            // 截断放在渲染之后：先截后渲染会让「对方没填城市」把打招呼那一档整档掏空，
            // 而库里排在后面的、不含 {city} 的句子本来是能给的
            if (bucket.size() >= capOf(type)) {
                continue;
            }
            bucket.add(IcebreakerItemVO.builder().id(row.getId()).content(content).tagName(tagName).build());
        }
        return IcebreakerSuggestVO.builder()
                .targetUserId(target)
                .nickname(peer == null ? null : peer.getNickname())
                .commonTags(new ArrayList<>(shared.values()))
                .greetings(greetings)
                .topics(topics)
                .tagOpeners(tagOpeners)
                .build();
    }

    @Override
    public void use(Long id) {
        // 短路：id 为 null 时不去查库，直接按「已下架」回一句——用户手上的列表是几十秒前拉的，
        // 无论这条是被停用还是被删掉，正确动作都是重新拉一次换一条
        BusinessException.throwUnless(id != null && icebreakerMapper.increaseUseCount(id) > 0,
                ResultCode.DATING_ICEBREAKER_GONE);
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 双方共有的兴趣标签，{@code tagId -> 标签名}，按<b>我自己</b>拖的顺序（最重要的兴趣在前）。
     *
     * <p>只收录字典里还存在的标签：已被后台删除的绑定行取不出名字，
     * 留在结果里会让 {@code commonTags} 出现一个 {@code null}，也会让绑在它上面的开场白
     * 被 {@link #suggest} 当成「标签还在」的那一档处理。
     *
     * @return 没有共同标签（或没有目标）时返回空 map，调用方按「只给通用文案」处理
     */
    private Map<Long, String> sharedTags(Long currentUserId, Long targetUserId) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (currentUserId == null || targetUserId == null) {
            return result;
        }
        List<UserTag> rows = userTagMapper.selectByUserIds(List.of(currentUserId, targetUserId));
        if (rows.isEmpty()) {
            return result;
        }
        Set<Long> mine = new LinkedHashSet<>();
        Set<Long> theirs = new HashSet<>();
        for (UserTag row : rows) {
            if (currentUserId.equals(row.getUserId())) {
                mine.add(row.getTagId());
            } else {
                theirs.add(row.getTagId());
            }
        }
        mine.retainAll(theirs);
        if (mine.isEmpty()) {
            return result;
        }
        Map<Long, InterestTag> dict = new LinkedHashMap<>();
        for (InterestTag tag : interestTagMapper.selectBatchIds(mine)) {
            dict.put(tag.getId(), tag);
        }
        // 遍历 mine 而不是 dict：mine 是有序集合，dict 的顺序是批量查回来的顺序
        for (Long tagId : mine) {
            InterestTag tag = dict.get(tagId);
            if (tag != null) {
                result.put(tagId, tag.getName());
            }
        }
        return result;
    }

    private List<IcebreakerItemVO> bucketOf(IcebreakerType type, List<IcebreakerItemVO> greetings,
                                            List<IcebreakerItemVO> topics, List<IcebreakerItemVO> tagOpeners) {
        return switch (type) {
            case GREETING -> greetings;
            case TOPIC -> topics;
            case TAG_OPENER -> tagOpeners;
        };
    }

    private int capOf(IcebreakerType type) {
        return switch (type) {
            case GREETING -> Icebreaker.SUGGEST_GREETING_MAX;
            case TOPIC -> Icebreaker.SUGGEST_TOPIC_MAX;
            case TAG_OPENER -> Icebreaker.SUGGEST_TAG_OPENER_MAX;
        };
    }
}
