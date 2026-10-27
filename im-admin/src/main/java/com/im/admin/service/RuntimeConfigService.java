package com.im.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.admin.config.AdminConfigKey;
import com.im.admin.dto.vo.ConfigItemVO;
import com.im.admin.entity.ConfigItem;
import com.im.admin.mapper.ConfigItemMapper;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.sensitive.SensitiveWordFilter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 运行期配置的读写与落库（四期第四批的后端核心）。
 *
 * <h2>三份数据各有各的主人，这里只当中转</h2>
 *
 * <ul>
 *   <li><b>目录</b>（有哪些项、叫什么、能填多少）在 {@link AdminConfigKey}，只有代码；</li>
 *   <li><b>状态</b>（谁把哪一项改成了什么）在 {@code im_config}，只有库；</li>
 *   <li><b>运行态</b>（此刻全服按哪个值办事）在 {@link ImProperties} 那个单例的字段上
 *       和 {@link SensitiveWordFilter} 的内存标志上。</li>
 * </ul>
 *
 * <p>本类的全部工作就是把第二份搬到第三份上，并在搬运前后各问一次第一份「这个键允许搬吗」。
 * 它自己不存任何值——一个字段都没有（除了一份启动快照），所以也不存在「这里记的和那边不一致」
 * 这种问题。
 *
 * <h2>写的目标是属性对象的字段，不是 Spring 的 {@code Environment}</h2>
 *
 * <p>{@code Environment} 里那份才是 {@code @ConfigurationProperties} 绑定的来源，
 * 改它会牵动重新绑定（别的 bean 可能正拿着旧值），而且它对任意 key 开放，
 * 等于绕开了白名单。改 {@code ImProperties} 的字段只影响这一组已被点名承认的旋钮，
 * 且各读取点都是「每次调用现取」的写法（逐个核对过，结论见开发指南的系统配置章节）。
 *
 * <h2>先落库、后改运行态</h2>
 *
 * <p>{@link #update} 的这两步顺序是有意的：库写失败（连接断、表还没建）时运行态一动没动，
 * 管理员收到报错、界面维持原值，账实相符；反过来先改运行态的话，报错的那一瞬间
 * 全服其实已经按新值跑了，而后台显示「失败」——这种「其实生效了但告诉用户没生效」
 * 是最难复现的一类问题。运行态那一步本身不会失败：值已经过校验，分支由编译器穷尽性保证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final ImProperties imProperties;
    /**
     * 敏感词开关这一项的运行态在过滤器上而不是 {@link ImProperties} 上，所以本类必须依赖它。
     *
     * <p>构造注入顺带保证了一件事：它的 {@code @PostConstruct}（读配置文件的开关初值）
     * 早于本类的 {@link #captureStartupDefaults()}，否则启动默认值会快照成字段声明处的
     * {@code true}，而配置文件里可能写的是 {@code false}。
     */
    private final SensitiveWordFilter sensitiveWordFilter;
    private final ConfigItemMapper configItemMapper;

    /** key → 进程启动那一刻的运行态（配置文件与代码默认值），「恢复默认」写回的就是它 */
    private final Map<AdminConfigKey, String> startupDefaults = new EnumMap<>(AdminConfigKey.class);

    /** 上一轮应用过的 {@code im_config} 内容指纹，见 {@link #refreshFromDb()} */
    private volatile String lastSignature;

    @PostConstruct
    void captureStartupDefaults() {
        for (AdminConfigKey key : AdminConfigKey.values()) {
            startupDefaults.put(key, readRuntime(key));
        }
        log.info("[系统配置] 启动默认值快照完成，共 {} 项（im_config 里的覆盖项将在应用就绪后盖上来）",
                startupDefaults.size());
    }

    /* ==================== 对外 ==================== */

    /**
     * 目录全量 + 当前运行态 + 库里的覆盖信息，一次给全，前端按 {@code group} 分区渲染。
     *
     * <p>整张 {@code im_config} 只有几十行，所以是全量取回再在内存里与目录合并，
     * 不逐项查二十七个 key——那是一整轮数据库往交换一次。
     */
    public List<ConfigItemVO> listAll() {
        Map<String, ConfigItem> rows = loadRows();
        List<ConfigItemVO> list = new ArrayList<>(AdminConfigKey.values().length);
        for (AdminConfigKey key : AdminConfigKey.values()) {
            list.add(toVO(key, rows.get(key.key())));
        }
        return list;
    }

    /**
     * 改一项：校验 → 落库 → 改运行态，返回改完后的那一项（前端以此回填，不相信自己发出去的值）。
     *
     * @param operatorId 操作人（后台账号 ID），只用于日志与 {@code update_by}
     */
    public ConfigItemVO update(String key, String rawValue, Long operatorId) {
        AdminConfigKey item = AdminConfigKey.of(key);
        BusinessException.throwIf(item == null, ResultCode.ADMIN_CONFIG_KEY_UNKNOWN);
        Object typed = parseAndCheck(item, rawValue);

        ConfigItem row = configItemMapper.selectOne(Wrappers.<ConfigItem>lambdaQuery()
                .eq(ConfigItem::getConfigKey, item.key()));
        String normalized = String.valueOf(typed);
        if (row == null) {
            row = new ConfigItem();
            row.setConfigKey(item.key());
            row.setConfigValue(normalized);
            row.setUpdateBy(operatorId);
            configItemMapper.insert(row);
        } else {
            ConfigItem update = new ConfigItem();
            update.setId(row.getId());
            update.setConfigValue(normalized);
            update.setUpdateBy(operatorId);
            configItemMapper.updateById(update);
            row.setConfigValue(normalized);
            row.setUpdateBy(operatorId);
            // 刷新处理器把时间填在传给 updateById 的那个对象上，不是 row；不补这一句，
            // 界面刚改完那一行会显示成「最后修改时为空」
            row.setUpdateTime(update.getUpdateTime());
        }

        String previous = readRuntime(item);
        writer(item).accept(typed);
        log.info("[系统配置] {}（{}）由 {} 改为 {}，操作人 {}（本节点立即生效，其他节点靠定时收敛）",
                item.title(), item.key(), previous, normalized, operatorId);
        return toVO(item, row);
    }

    /**
     * 恢复默认：物理删掉库里那一行，并把运行态写回启动默认值。
     *
     * <p>是删除而不是把值改成默认值：留下那一行，「这项跟随配置文件」的语义就永久丢了
     * （以后改配置文件里的初值，这一项不再跟着变，而界面上看不出差别）。
     */
    public ConfigItemVO reset(String key, Long operatorId) {
        AdminConfigKey item = AdminConfigKey.of(key);
        BusinessException.throwIf(item == null, ResultCode.ADMIN_CONFIG_KEY_UNKNOWN);
        int deleted = configItemMapper.delete(Wrappers.<ConfigItem>lambdaQuery()
                .eq(ConfigItem::getConfigKey, item.key()));
        String previous = readRuntime(item);
        String target = startupDefaults.get(item);
        writer(item).accept(parseAndCheck(item, target));
        log.info("[系统配置] {}（{}）由 {} 恢复为启动默认值 {}，原库内覆盖行 {}，操作人 {}",
                item.title(), item.key(), previous, target, deleted > 0 ? "已删除" : "本来就没有", operatorId);
        return toVO(item, null);
    }

    /** 当前运行态的字符串形式，{@link AdminConfigService} 拿它回填敏感词开关的 VO */
    public String readRuntime(AdminConfigKey key) {
        return String.valueOf(reader(key).get());
    }

    /** 启动默认值（未被覆盖时页面显示的那一句「默认 xxx」） */
    public String startupDefault(AdminConfigKey key) {
        return startupDefaults.get(key);
    }

    /**
     * 这一项目前有没有库内覆盖行。单独开一条按 key 的计数而不是用 {@link #listAll()} 找，
     * 是因为敏感词开关那个旧端点只要这一个布尔值，拉整个目录（二十七项 + 一次全表读）不值得。
     */
    public boolean isOverridden(AdminConfigKey key) {
        try {
            return configItemMapper.selectCount(Wrappers.<ConfigItem>lambdaQuery()
                    .eq(ConfigItem::getConfigKey, key.key())) > 0;
        } catch (Exception e) {
            log.warn("[系统配置] 查 {} 的覆盖行失败，按「未覆盖」展示：{}", key.key(), e.getMessage());
            return false;
        }
    }

    /* ==================== 多实例与重启后的收敛 ==================== */

    /**
     * 把库里的覆盖项搬到运行态上，内容与上一轮相同则整轮跳过。
     *
     * <p>两个方向共用这一条路径：① 启动完成后第一次调（把重启前存下的覆盖搬回来）；
     * ② 定时任务每 30 秒调一次（多实例部署时，另一个节点改的库靠这里收敛过来）。
     *
     * <h2>为什么是「全目录覆盖式应用」而不是「只应库里有的那几行」</h2>
     *
     * <p>因为「恢复默认」是<b>删行</b>。只应库里现有的行的话，另一个节点上的定时任务
     * 会发现那一行根本不存在、也就什么都不做，于是那台机器继续按旧覆盖值跑，
     * 界面上却已经显示「已恢复默认」——不重启就不一致。改成每一轮都把二十七项全搬一遍
     * （库里有就用库里的值，没有就用启动默认值），删除才能收敛。代价是一次读二十几行
     * 外加二十七次 setter，只在指纹真变过时跑。
     *
     * <p>这样做的成立前提是：运行态只允许从本类这里改。目前确实只有一处写入路径（后台配置接口），
     * 以后谁加了一条绕过本类改 {@code ImProperties} 的路，这一轮会把它写回去。
     *
     * <p>指纹用「全部 key=value 排序后拼接」而不是 {@code MAX(update_time)}：删掉最大那一行时
     * MAX 会退回次大值，而删掉中间任意一行时它根本不变——后者才是常态。全表最多二十几行，
     * 排序拼串的成本可以忽略。
     *
     * <p>表不存在（后端先上、SQL 后执行）时只记一条 warn 并跳过：配置项都是增强，
     * 拿配置文件的值跑总比启动失败强。
     *
     * @return 本轮按库内覆盖跑的项数，跳过时为 0
     */
    public int refreshFromDb() {
        List<ConfigItem> rows;
        try {
            rows = configItemMapper.selectList(null);
        } catch (Exception e) {
            log.warn("[系统配置] 读取 im_config 失败，本轮跳过（沿用当前运行态）：{}", e.getMessage());
            return 0;
        }
        String signature = signatureOf(rows);
        if (signature.equals(lastSignature)) {
            return 0;
        }
        this.lastSignature = signature;
        Map<String, ConfigItem> byKey = new HashMap<>(rows.size());
        for (ConfigItem row : rows) {
            if (AdminConfigKey.of(row.getConfigKey()) == null) {
                // 目录里已经没有这一项了：它不参与任何判定，只留一句日志让管理员能看见。
                // 关键是这里绝不能抛异常——代码里删一个功能，不该让老库残留的一行把后端带不起。
                log.warn("[系统配置] im_config 里的 {} 已不在服务端白名单目录内，忽略这一行（可能对应的功能已被移除）",
                        row.getConfigKey());
                continue;
            }
            byKey.put(row.getConfigKey(), row);
        }
        int overridden = 0;
        for (AdminConfigKey item : AdminConfigKey.values()) {
            ConfigItem row = byKey.get(item.key());
            String target = row == null ? startupDefaults.get(item) : row.getConfigValue();
            try {
                writer(item).accept(parseAndCheck(item, target));
                if (row != null) {
                    overridden++;
                }
            } catch (RuntimeException e) {
                // 库内值通不过当前校验（比如改了代码把取值范围收窄、库里那条老值出界了）：
                // 保留运行态里现有值，比让服务起不来好，也比悄悄夹一个值进范围更诚实
                log.warn("[系统配置] {} 的库内值 {} 未通过当前校验（{}），沿用运行态 {}",
                        item.key(), target, e.getMessage(), readRuntime(item));
            }
        }
        log.info("[系统配置] 运行态收敛完成：{} 项按库内覆盖，其余 {} 项回到启动默认值",
                overridden, AdminConfigKey.values().length - overridden);
        return overridden;
    }

    /* ==================== 校验与读写分支 ==================== */

    /**
     * 字符串 → 带类型的值，同时是那道范围闸门。
     *
     * <p>校验只在服务端做这一份，前端那个数字框的 min/max 是从 {@link #listAll()} 里拿的同一份
     * {@code AdminConfigKey}——前端拦不住的人照样能直接调接口，所以真正的门只能在这里。
     */
    private Object parseAndCheck(AdminConfigKey item, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        return switch (item.type()) {
            case BOOLEAN -> {
                BusinessException.throwUnless("true".equals(value) || "false".equals(value),
                        ResultCode.BAD_REQUEST, item.title() + "只能是 true 或 false，收到的是「" + value + "」");
                yield Boolean.valueOf(value);
            }
            case NUMBER -> {
                long number;
                try {
                    number = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            item.title() + "必须是整数，收到的是「" + value + "」");
                }
                BusinessException.throwIf(number < item.min() || number > item.max(), ResultCode.BAD_REQUEST,
                        item.title() + "的允许范围是 [" + item.min() + ", " + item.max() + "]，收到的是 " + number);
                yield number;
            }
        };
    }

    /**
     * 读运行态。<b>穷尽 switch 表达式，没有 default</b>：加了枚举常量却忘了这里，编译直接不过。
     *
     * <p>返回 {@code Supplier} 而不是值，是为了让 {@link #captureStartupDefaults()} 能在
     * 需要的时候再取一次当时的值（快照与读取共用同一分支表，不出现第二套取法）。
     */
    private Supplier<Object> reader(AdminConfigKey key) {
        return switch (key) {
            case SENSITIVE_FILTER_ENABLED -> sensitiveWordFilter::isEnabled;
            case MESSAGE_RECALL_LIMIT_SECONDS -> () -> imProperties.getMessage().getRecallLimitSeconds();
            case MESSAGE_MAX_TEXT_LENGTH -> () -> imProperties.getMessage().getMaxTextLength();
            case MESSAGE_RETENTION_DAYS -> () -> imProperties.getMessage().getRetentionDays();
            case CACHE_ENABLED -> () -> imProperties.getCache().isEnabled();
            case RATE_LIMIT_ENABLED -> () -> imProperties.getRateLimit().isEnabled();
            case RATE_LIMIT_GLOBAL_COUNT -> () -> imProperties.getRateLimit().getGlobalCount();
            case RATE_LIMIT_GLOBAL_SECONDS -> () -> imProperties.getRateLimit().getGlobalSeconds();
            case RATE_LIMIT_WS_CHAT_COUNT -> () -> imProperties.getRateLimit().getWsChatCount();
            case RATE_LIMIT_WS_CHAT_SECONDS -> () -> imProperties.getRateLimit().getWsChatSeconds();
            case MODERATION_ENABLED -> () -> imProperties.getModeration().isEnabled();
            case MODERATION_AUDIT_ENABLED -> () -> imProperties.getModeration().isAuditEnabled();
            case INTELLIGENCE_ENABLED -> () -> imProperties.getIntelligence().isEnabled();
            case CALL_ENABLED -> () -> imProperties.getCall().isEnabled();
            case CALL_GROUP_ENABLED -> () -> imProperties.getCall().isGroupEnabled();
            case CALL_GROUP_MAX_SLOTS -> () -> imProperties.getCall().getGroupMaxSlots();
            case CALL_RING_TIMEOUT_SECONDS -> () -> imProperties.getCall().getRingTimeoutSeconds();
            case CALL_MAX_DURATION_SECONDS -> () -> imProperties.getCall().getMaxDurationSeconds();
            case WALLET_ENABLED -> () -> imProperties.getWallet().isEnabled();
            case WALLET_TRANSFER_ENABLED -> () -> imProperties.getWallet().isTransferEnabled();
            case WALLET_CHECKIN_BONUS -> () -> imProperties.getWallet().getCheckinBonus();
            case WALLET_MAX_GIFT_COUNT -> () -> imProperties.getWallet().getMaxGiftCount();
            case WALLET_TRANSFER_MAX -> () -> imProperties.getWallet().getTransferMax();
            case WALLET_PACKET_MAX -> () -> imProperties.getWallet().getPacketMax();
            case WALLET_PACKET_EXPIRE_HOURS -> () -> imProperties.getWallet().getPacketExpireHours();
            case LINKMIC_ENABLED -> () -> imProperties.getLinkmic().isEnabled();
            case LINKMIC_MAX_SLOTS -> () -> imProperties.getLinkmic().getMaxSlots();
        };
    }

    /**
     * 写运行态。与 {@link #reader} 一样是穷尽 switch 表达式——
     * 只补 reader 不补 writer 是最容易漏的一半，而这里的编译错误把它兜住了。
     *
     * <p>NUMBER 一律先经 {@link #number} 验明是数字并取成长整型，再按目标字段的类型收窄：
     * int 字段调 {@code intValue()}，long 字段（签到奖励、转账上限、红包上限）调 {@code longValue()}。
     * 校验过的值必然落在枚举声明的范围内，而所有 {@code int} 字段的上界都远小于
     * {@link Integer#MAX_VALUE}（最大的一档是 86400 秒），收窄不会静默溢出。
     */
    private Consumer<Object> writer(AdminConfigKey key) {
        return switch (key) {
            case SENSITIVE_FILTER_ENABLED -> value -> sensitiveWordFilter.setEnabled((Boolean) value);
            case MESSAGE_RECALL_LIMIT_SECONDS ->
                    value -> imProperties.getMessage().setRecallLimitSeconds(number(key, value).intValue());
            case MESSAGE_MAX_TEXT_LENGTH ->
                    value -> imProperties.getMessage().setMaxTextLength(number(key, value).intValue());
            case MESSAGE_RETENTION_DAYS ->
                    value -> imProperties.getMessage().setRetentionDays(number(key, value).intValue());
            case CACHE_ENABLED -> value -> imProperties.getCache().setEnabled((Boolean) value);
            case RATE_LIMIT_ENABLED -> value -> imProperties.getRateLimit().setEnabled((Boolean) value);
            case RATE_LIMIT_GLOBAL_COUNT ->
                    value -> imProperties.getRateLimit().setGlobalCount(number(key, value).intValue());
            case RATE_LIMIT_GLOBAL_SECONDS ->
                    value -> imProperties.getRateLimit().setGlobalSeconds(number(key, value).intValue());
            case RATE_LIMIT_WS_CHAT_COUNT ->
                    value -> imProperties.getRateLimit().setWsChatCount(number(key, value).intValue());
            case RATE_LIMIT_WS_CHAT_SECONDS ->
                    value -> imProperties.getRateLimit().setWsChatSeconds(number(key, value).intValue());
            case MODERATION_ENABLED -> value -> imProperties.getModeration().setEnabled((Boolean) value);
            case MODERATION_AUDIT_ENABLED -> value -> imProperties.getModeration().setAuditEnabled((Boolean) value);
            case INTELLIGENCE_ENABLED -> value -> imProperties.getIntelligence().setEnabled((Boolean) value);
            case CALL_ENABLED -> value -> imProperties.getCall().setEnabled((Boolean) value);
            case CALL_GROUP_ENABLED -> value -> imProperties.getCall().setGroupEnabled((Boolean) value);
            case CALL_GROUP_MAX_SLOTS ->
                    value -> imProperties.getCall().setGroupMaxSlots(number(key, value).intValue());
            case CALL_RING_TIMEOUT_SECONDS ->
                    value -> imProperties.getCall().setRingTimeoutSeconds(number(key, value).intValue());
            case CALL_MAX_DURATION_SECONDS ->
                    value -> imProperties.getCall().setMaxDurationSeconds(number(key, value).intValue());
            case WALLET_ENABLED -> value -> imProperties.getWallet().setEnabled((Boolean) value);
            case WALLET_TRANSFER_ENABLED -> value -> imProperties.getWallet().setTransferEnabled((Boolean) value);
            case WALLET_CHECKIN_BONUS ->
                    value -> imProperties.getWallet().setCheckinBonus(number(key, value).longValue());
            case WALLET_MAX_GIFT_COUNT ->
                    value -> imProperties.getWallet().setMaxGiftCount(number(key, value).intValue());
            case WALLET_TRANSFER_MAX ->
                    value -> imProperties.getWallet().setTransferMax(number(key, value).longValue());
            case WALLET_PACKET_MAX ->
                    value -> imProperties.getWallet().setPacketMax(number(key, value).longValue());
            case WALLET_PACKET_EXPIRE_HOURS ->
                    value -> imProperties.getWallet().setPacketExpireHours(number(key, value).intValue());
            case LINKMIC_ENABLED -> value -> imProperties.getLinkmic().setEnabled((Boolean) value);
            case LINKMIC_MAX_SLOTS ->
                    value -> imProperties.getLinkmic().setMaxSlots(number(key, value).intValue());
        };
    }

    /** 返回装箱的 {@code Long} 而不是原始 {@code long}：调用方要的就是「再取一次值」这个动作。 */
    private Long number(AdminConfigKey key, Object value) {
        BusinessException.throwIf(!(value instanceof Number), ResultCode.BUSINESS_ERROR,
                key.title() + "收到了非数字的值：" + value);
        return ((Number) value).longValue();
    }

    /* ==================== 内部 ==================== */

    private Map<String, ConfigItem> loadRows() {
        List<ConfigItem> rows;
        try {
            rows = configItemMapper.selectList(null);
        } catch (Exception e) {
            log.warn("[系统配置] 读取 im_config 失败，页面按「全部未覆盖」展示：{}", e.getMessage());
            return Map.of();
        }
        Map<String, ConfigItem> map = new HashMap<>(rows.size());
        rows.forEach(row -> map.put(row.getConfigKey(), row));
        return map;
    }

    private ConfigItemVO toVO(AdminConfigKey item, ConfigItem row) {
        ConfigItemVO vo = new ConfigItemVO();
        vo.setKey(item.key());
        vo.setGroup(item.group().code());
        vo.setGroupLabel(item.group().label());
        vo.setType(item.type().name());
        vo.setTitle(item.title());
        vo.setDescription(item.description());
        vo.setMin(item.min());
        vo.setMax(item.max());
        vo.setOptions(item.type() == AdminConfigKey.Type.BOOLEAN ? AdminConfigKey.booleanOptions() : null);
        vo.setValue(readRuntime(item));
        vo.setStartupDefault(startupDefaults.get(item));
        vo.setOverridden(row != null);
        vo.setUpdateBy(row == null ? null : row.getUpdateBy());
        vo.setUpdateTime(row == null ? null : row.getUpdateTime());
        return vo;
    }

    private String signatureOf(List<ConfigItem> rows) {
        return rows.stream()
                .map(row -> row.getConfigKey() + "=" + row.getConfigValue())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("&"));
    }
}
