package com.im.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.admin.dto.vo.SensitiveWordImportVO;
import com.im.admin.dto.vo.SensitiveWordVO;
import com.im.admin.entity.SensitiveWord;
import com.im.admin.mapper.SensitiveWordMapper;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.PageQuery;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在线敏感词库的增删查与字典树同步（四期第四批）。
 *
 * <h2>每一次改词都立刻重建，定时任务只是兜底</h2>
 *
 * <p>后台加/删一个词后本方法会当场 {@link #reloadFromDb()} 一次，改动在<b>本节点</b>是即时的；
 * 别的节点靠 {@code RuntimeConfigSyncTask} 每 30 秒比一次签名收敛。之所以两处都做而不只靠定时任务：
 * 管理员加完词刷新页面看到「生效词条数」没变，会立刻重试一遍——那与「没加上」是同一种体验，
 * 而实际已经加上了。让改动的回执里就带上重建后的数字，比让他等一个后台轮询周期诚实。
 *
 * <h2>库里存的必须是折叠后的词</h2>
 *
 * <p>{@code ＡＢＣ}、{@code ABC}、{@code abc} 在字典树里是同一个键，如果原样入库，
 * {@code uk_word} 挡不住后两种，词表会长出三条看起来不同、行为完全一样的记录，
 * 而界面上「删除 {@code abc}」之后 {@code ＡＢＣ} 仍然在遮——没人能解释这件事。
 * 所以入库前先过 {@link SensitiveWordFilter#foldWord}，唯一键因此才是真的唯一键。
 *
 * <h2>合法性判定不在这里重写一遍</h2>
 *
 * <p>用的是过滤器装载文件词库时那一份 {@link SensitiveWordFilter#isValidWord}：
 * 单字词、带标点符号的词、正则元字符那些，在这里再写一份校验迟早和那边漂移，
 * 结果就是「同一个词文件里装不进去、后台能装进去」这类谁也说不清的现象。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    /** 词条入库长度上限，与 {@code im_sensitive_word.word} 的列宽一致 */
    private static final int WORD_MAX_LENGTH = 64;

    /** 一次提交最多拆出多少个词：防一句请求把字典树重建几十次 */
    private static final int MAX_TOKENS_PER_REQUEST = 500;

    private final SensitiveWordMapper sensitiveWordMapper;
    private final SensitiveWordFilter sensitiveWordFilter;

    /** 上一轮看到的词表指纹，见 {@link #refreshIfChanged()} */
    private volatile String lastSignature;

    /* ==================== 查 ==================== */

    /**
     * 分页看后台词库。{@code keyword} 是折叠后再 LIKE 的：不折叠的话搜「ABC」搜不到库里那条
     * {@code abc}，而库里存的本就是折叠形式——两边同一口径才对得上。
     */
    public PageResult<SensitiveWordVO> page(PageQuery query) {
        String keyword = query.getKeyword();
        LambdaQueryWrapper<SensitiveWord> wrapper = Wrappers.<SensitiveWord>lambdaQuery();
        if (TextUtil.isNotBlank(keyword)) {
            wrapper.like(SensitiveWord::getWord, SensitiveWordFilter.foldWord(keyword));
        }
        wrapper.orderByDesc(SensitiveWord::getId);
        Page<SensitiveWord> page = sensitiveWordMapper.selectPage(query.toPage(), wrapper);
        List<SensitiveWordVO> rows = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(rows, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 后台词库条数（不含文件词库那 19643 条），页面顶栏显示「后台加词 N 条 / 全服生效 M 条」用 */
    public long countDbWords() {
        try {
            return sensitiveWordMapper.selectCount(null);
        } catch (Exception e) {
            log.warn("[敏感词] 统计 im_sensitive_word 失败，按 0 展示：{}", e.getMessage());
            return 0L;
        }
    }

    /* ==================== 改 ==================== */

    /**
     * 加一个词（或一批词，逗号/换行分隔），返回本次的计数结果。
     *
     * <p>整体不做事务：一部分词进去、另一部分因撞唯一键失败是可接受的中间态，
     * 因为每个词都是独立的一行、彼此没有业务关联；而 {@code reloadFromDb()} 读的是
     * <b>库里的全部行</b>，所以就算这里中途抛错，字典树也仍然是与库一致的状态。
     * 真正的重复插入已在下面按词查过一次，只剩并发下的极窄窗口，由唯一键兜住并转成一句可读的报错。
     */
    public SensitiveWordImportVO add(String rawWords, String remark, Long operatorId) {
        List<String> tokens = SensitiveWordFilter.splitTokens(rawWords);
        BusinessException.throwIf(tokens.isEmpty(), ResultCode.BAD_REQUEST, "没有解析到有效词条");
        BusinessException.throwIf(tokens.size() > MAX_TOKENS_PER_REQUEST, ResultCode.BAD_REQUEST,
                "一次最多提交 " + MAX_TOKENS_PER_REQUEST + " 个词条，本次拆出 " + tokens.size() + " 个，请分批提交");

        SensitiveWordImportVO result = new SensitiveWordImportVO();
        result.setTotal(tokens.size());
        String reason = normalizeRemark(remark);
        Set<String> batchSeen = new LinkedHashSet<>();
        int added = 0;
        int duplicated = 0;
        int invalid = 0;
        for (String word : tokens) {
            if (!SensitiveWordFilter.isValidWord(word) || word.length() > WORD_MAX_LENGTH) {
                invalid++;
                continue;
            }
            if (!batchSeen.add(word)) {
                // 同一批里自己重复（文件里那种「、」分隔连写两次）不查库，白省一次往返
                duplicated++;
                continue;
            }
            if (exists(word)) {
                duplicated++;
                continue;
            }
            SensitiveWord entity = new SensitiveWord();
            entity.setWord(word);
            entity.setRemark(reason);
            entity.setCreateBy(operatorId);
            try {
                sensitiveWordMapper.insert(entity);
                added++;
            } catch (DataIntegrityViolationException e) {
                // 只在并发撞 uk_word 时走到：本线程没加成，但库里已经有这一行了
                duplicated++;
            }
        }
        result.setAdded(added);
        result.setDuplicated(duplicated);
        result.setInvalid(invalid);
        // 一个都没入库就不重建：一次近两万条的重建不该由「全是重复词」这种提交触发
        result.setEffectiveCount(added > 0 ? reloadFromDb() : sensitiveWordFilter.getWordCount());
        log.info("[敏感词] 后台提交 {} 个词条：入库 {}、重复 {}、非法 {}，操作人 {}",
                tokens.size(), added, duplicated, invalid, operatorId);
        return result;
    }

    /**
     * 删一个词。按 ID 删而不是按词删：界面上那一行就是库里那一行，
     * 用词文本当条件的话，全角/大小写变体（虽然折叠后本不该有）会让删除打空而页面显示成功。
     */
    public int delete(Long id, Long operatorId) {
        SensitiveWord entity = sensitiveWordMapper.selectById(id);
        BusinessException.throwIf(entity == null, ResultCode.NOT_FOUND, "该词条不存在或已被删除");
        int deleted = sensitiveWordMapper.deleteById(id);
        int effective = reloadFromDb();
        log.info("[敏感词] 后台删除词条 {}（生效 {} 条），操作人 {}", entity.getWord(), effective, operatorId);
        return deleted;
    }

    /* ==================== 与字典树同步 ==================== */

    /**
     * 读全表并与文件词库求并集重建字典树。
     *
     * <p>传给 {@code reload} 的是<b>库里全部生效词条</b>而不是刚改的那一个：重建的语义是
     * 「新树 = 文件词条 ∪ 后台词条」，只有整份交过去，删掉的词才会真的消失。
     *
     * @return 重建后的生效词条总数
     */
    public int reloadFromDb() {
        List<SensitiveWord> rows;
        try {
            rows = sensitiveWordMapper.selectList(null);
        } catch (Exception e) {
            log.warn("[敏感词] 读取 im_sensitive_word 失败，字典树保持现状（文件词库仍在生效）：{}", e.getMessage());
            return sensitiveWordFilter.getWordCount();
        }
        List<String> words = new ArrayList<>(rows.size());
        rows.forEach(row -> words.add(row.getWord()));
        int effective = sensitiveWordFilter.reload(words);
        this.lastSignature = safeSignature();
        return effective;
    }

    /**
     * 定时任务入口：词表指纹没变就什么都不做。
     *
     * <p>为什么要有这一句：重建一次是几十毫秒级的 CPU 开销（近两万条），而词表一天可能一次都不变。
     * 无脑每 30 秒重建一次，等于用一个没人盯着的后台功能给全服消息链路常驻加一笔开销。
     *
     * <p>指纹取 {@code 条数|最后更新时间}（见 {@link SensitiveWordMapper#selectSignature()}），
     * 不用 {@code MAX(id)}：那个只认新增，删掉中间任意一行时它根本不变，
     * 表现成「后台把词删了、那个词却还在遮用户的消息且无人知道」。
     */
    public void refreshIfChanged() {
        String signature = safeSignature();
        if (signature.equals(lastSignature)) {
            return;
        }
        log.info("[敏感词] 检测到词表变化（{} -> {}），重建字典树", lastSignature, signature);
        reloadFromDb();
    }

    private String safeSignature() {
        try {
            String signature = sensitiveWordMapper.selectSignature();
            return signature == null ? "" : signature;
        } catch (Exception e) {
            // 表还没建（后端先上、SQL 后执行）：返回空串，下一轮再试，不影响文件词库
            log.warn("[敏感词] 读取词表签名失败，本轮跳过：{}", e.getMessage());
            return lastSignature == null ? "" : lastSignature;
        }
    }

    /* ==================== 内部 ==================== */

    private boolean exists(String word) {
        return sensitiveWordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getWord, word)) > 0;
    }

    private String normalizeRemark(String remark) {
        String cleaned = TextUtil.sanitize(remark);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private SensitiveWordVO toVO(SensitiveWord entity) {
        SensitiveWordVO vo = new SensitiveWordVO();
        vo.setId(entity.getId());
        vo.setWord(entity.getWord());
        vo.setRemark(entity.getRemark());
        vo.setCreateBy(entity.getCreateBy());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
