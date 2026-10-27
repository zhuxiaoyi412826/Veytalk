package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 后台运行期配置，对应 {@code im_config}。
 *
 * <p><b>这张表只存「被后台改过的那一项」</b>，不是全量配置快照：没被改过的配置项在这里
 * 根本没有行，运行态就是 {@code application.yml} 与代码默认值。目录（有哪些项、叫什么名、
 * 能不能改、取值范围）全部在 {@code AdminConfigKey} 那个枚举里，库里不重复一份——
 * 两边各存一份标题和说明，迟早会漂移成「界面上写的和代码里做的不一样」。
 *
 * <p>也因此<b>不需要给每个配置项 INSERT 种子行</b>：本批的 migration 只建表、不灌数据，
 * 表一开始是空的，管理员每改一项才长出一行。
 *
 * <p>继承 {@link AuditEntity}（无 {@code deleted} 列）：「恢复默认」是把这一行<b>物理删掉</b>，
 * 删掉之后该项就重新回到跟随配置文件的语义。软删的话 {@code uk_config_key} 不会释放，
 * 同一项就再也写不进来了。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_config")
public class ConfigItem extends AuditEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 配置键，与 {@code AdminConfigKey} 里的 key 一一对应；不在枚举里的键一律拒绝写入 */
    private String configKey;

    /**
     * 配置值，<b>一律以字符串存储</b>，按枚举声明的类型解释（{@code "true"} / {@code "120"}）。
     *
     * <p>不用 MySQL 的 JSON 列也不用多列分型：这一列的取值口径由服务端白名单独一份地裁决，
     * 存成什么形状只有本模块读得懂，多列类型只会让「恢复默认」和「新增一项」都变复杂。
     */
    private String configValue;

    /** 最后修改人（后台账号 ID），只用于「这项是谁改的」这一句能在日志与界面上说清 */
    private Long updateBy;
}
