package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 后台在线维护的敏感词，对应 {@code im_sensitive_word}。
 *
 * <p>它是 classpath 那份静态词库（im-common 的 {@code wordlists/sensitive_words.txt}，
 * 生效 19643 条）之上的<b>增量</b>，不是替代：装载时两者求并集重建字典树，
 * 所以删掉库里这一行只是撤掉后台加的那一个词，文件里的词一个都不会少。
 * 反过来说，<b>想屏蔽文件里的某个词、或者把误伤的词摘掉，做不到</b>——那要改代码重新打包，
 * 这是本批刻意的取舍：与其做一套「停用词表」去覆盖文件（多一份账本、多一处冲突），
 * 不如把能力限定在「加词」，并把边界说清楚。
 *
 * <p>物理删除（继承 {@link AuditEntity} 而非 {@code BaseEntity}）：{@code uk_word} 必须随着删除
 * 立刻释放，否则删过的词永远加不回来，界面上还会显示成「这个词已经存在」。
 *
 * <p>{@link #word} 存的是<b>折叠后</b>的形式（全角转半角 + 小写），与字典树里的键一致。
 * 这样 {@code uk_word} 才能真正挡住重复：{@code ＵＲ} 与 {@code ur} 在库里是同一行，
 * 而不是两行看起来不同、匹配时却完全等价的记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_sensitive_word")
public class SensitiveWord extends AuditEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 词条：已按过滤器口径折叠（全角→半角、统一小写），只含汉字与小写字母数字、至少 2 个字符 */
    private String word;

    /** 加词原因，可空。词表没有审计表兜底，这一列是唯一能问出「为什么加它」的地方 */
    private String remark;

    /** 创建人（后台账号 ID） */
    private Long createBy;
}
