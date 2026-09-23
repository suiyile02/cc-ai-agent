package com.ai.config;

import com.ai.config.props.AuthProps;
import com.ai.config.props.ChatProps;
import com.ai.config.props.ConcurrencyProps;
import com.ai.config.props.ContextProps;
import com.ai.config.props.CorsProps;
import com.ai.config.props.DemoProps;
import com.ai.config.props.IngestionProps;
import com.ai.config.props.LogRetentionProps;
import com.ai.config.props.RagProps;
import com.ai.config.props.SemanticCacheProps;
import com.ai.config.props.SessionTitleProps;
import com.ai.config.props.StorageProps;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 业务自定义配置({@code app.*})的聚合根——<b>只做分组, 不放字段</b>。
 *
 * <p>各段实体在 {@code com.ai.config.props} 下按前缀一一对应(RagProps ↔ app.rag.*,
 * ContextProps ↔ app.context.* 及其嵌套…), 改哪段配置就打开哪个文件。
 *
 * <p><b>{@code ignoreUnknownFields = false}(严格绑定)是本类的核心约定</b>: Spring Boot 4
 * 默认会静默忽略 yaml 里绑不上任何字段的 {@code app.*} 键, 于是"改了配置没生效"和"配置项
 * 已随重构删除却还留在 yaml 里"这两类问题都无声无息——本项目吃过这个亏(见 AGENTS.md 踩坑记录)。
 * 打开后拼错键名直接启动失败, 配置漂移不可能存在。
 *
 * <p>少数键<b>不走绑定</b>(装配期开关, 字段拿到也来不及参与决策), 必须在
 * {@link #EXEMPT_UNKNOWN_KEYS} 里逐条登记; 由 {@code AppPropertiesBindingGuardTest} 断言
 * "未绑定的键只能来自这份清单", 从而既保住严格绑定, 又不会误伤这类键。
 *
 * <p><b>默认值策略(方案一: Java 单一事实来源)</b>: application.yaml 只保留两类键——
 * ①环境相关、需经环境变量注入的值(端点/口令/集合名/模型标签/CORS 来源),
 * ②安全基线与成本闸门(kb-only、dev-user-header、seed-enabled、各类超时与上限)。
 * 其余业务调参只在 props 类里写一次。新增配置项时<b>不要</b>顺手往 yaml 抄一份默认值。
 */
@Data
@ConfigurationProperties(prefix = AppProperties.PREFIX, ignoreUnknownFields = false)
public class AppProperties {

    /** 绑定前缀; {@code AppPropertiesExemptionConfig} 与豁免清单都以它为锚点。 */
    public static final String PREFIX = "app";

    /**
     * 允许存在于 {@code app.*} 命名空间下、但故意不绑定到字段的键(全限定名)。
     *
     * <p>准入标准只有一条: <b>该键在 bean 创建之前就被消费</b>(如 {@code @ConditionalOnProperty}
     * 选实现), 因此加个字段接住它只会造出一个"看着能改其实没用"的假旋钮。不符合该标准的键
     * 一律应当有字段, 让严格绑定去守它。
     */
    public static final Set<String> EXEMPT_UNKNOWN_KEYS = Set.of(
            // redis|memory: 两个 LoginAttemptLimiter 实现靠 @ConditionalOnProperty 二选一,
            // RedisReadinessProbe 另读原文做启动自检
            "app.auth.rate-limit-backend");

    private RagProps rag = new RagProps();
    private StorageProps storage = new StorageProps();
    private ChatProps chat = new ChatProps();
    private AuthProps auth = new AuthProps();
    private ContextProps context = new ContextProps();
    private DemoProps demo = new DemoProps();
    private CorsProps cors = new CorsProps();
    private LogRetentionProps logRetention = new LogRetentionProps();
    private SemanticCacheProps semanticCache = new SemanticCacheProps();
    private IngestionProps ingestion = new IngestionProps();
    private ConcurrencyProps concurrency = new ConcurrencyProps();
    private SessionTitleProps sessionTitle = new SessionTitleProps();
}
