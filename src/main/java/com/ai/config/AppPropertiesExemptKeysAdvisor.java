package com.ai.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.stereotype.Component;

/**
 * 让 {@link AppProperties#EXEMPT_UNKNOWN_KEYS} 登记的键在严格绑定下合法存在。
 *
 * <h2>要解决的问题</h2>
 * {@code @ConfigurationProperties(ignoreUnknownFields = false)}(见 {@link AppProperties})会把
 * "配置里出现、但没绑到任何字段"的 {@code app.*} 键判为启动失败。绝大多数时候这正是我们要的
 * (拼错键名、重构后残留的死配置会立刻暴露), 但有一类键天生不该有字段: 它在 bean 创建<b>之前</b>就被
 * 消费掉——{@code app.auth.rate-limit-backend} 靠 {@code @ConditionalOnProperty} 在两个限流实现间
 * 二选一, 给它开个字段等于造一个"看着能改其实没用"的假旋钮。
 *
 * <h2>为什么这样豁免是安全的</h2>
 * 关键在于只放行<b>清单点名的那几条 unbound 记录</b>, 其余原样抛出:
 * <ul>
 *   <li>{@link UnboundConfigurationPropertiesException#getUnboundProperties()} 带回了具体是哪几个键,
 *       于是这里做的是"逐条比对", 不是"吞掉整个异常";</li>
 *   <li>因此把 {@code app.rag.top-k} 写成 {@code app.rag.topk} 依然会启动失败——它不在清单里;</li>
 *   <li>清单本身由 {@code AppPropertiesBindingGuardTest} 反向校验: 清单里有、实际配置里没有时,
 *       测试报"假旋钮", 防止豁免项变成永久僵尸。</li>
 * </ul>
 *
 * <h2>为什么不用"改属性源枚举视图"</h2>
 * Boot 的未绑定检查({@code NoUnboundElementsBindHandler})只看可迭代属性源的枚举结果, 理论上把豁免键
 * 从枚举里剔掉更"正统"。实测走不通: 那个可迭代的 {@code configurationSources} 是在 Environment 准备
 * 阶段 attach 的, 缓存按底层源实例键控, 任何 {@code BeanFactoryPostProcessor} 再去替换都晚了一步
 * (要么找不到它, 要么绑定仍读到旧缓存)。这里用的 advisor 是 Boot 自己开放的扩展点, 依赖面反而更小。
 */
@Component
public class AppPropertiesExemptKeysAdvisor implements ConfigurationPropertiesBindHandlerAdvisor {

    @Override
    public BindHandler apply(BindHandler bindHandler) {
        return new ExemptKeysHandler(bindHandler);
    }

    /** 仅在 {@code app} 顶层绑定的收尾处过滤豁免键, 其它前缀一律原样透传。 */
    private static final class ExemptKeysHandler extends AbstractBindHandler {

        private ExemptKeysHandler(BindHandler parent) {
            super(parent);
        }

        @Override
        public void onFinish(ConfigurationPropertyName name, Bindable<?> target,
                             BindContext context, Object result) throws Exception {
            try {
                super.onFinish(name, target, context, result);
            } catch (UnboundConfigurationPropertiesException e) {
                if (!AppProperties.PREFIX.equals(name.toString())) {
                    throw e;
                }
                rethrowWithoutExempt(e);
            }
        }

        /** 剩下的未绑定键非空就继续报错, 全空才认为这次绑定通过。 */
        private void rethrowWithoutExempt(UnboundConfigurationPropertiesException e) {
            var remaining = e.getUnboundProperties().stream()
                    .filter(p -> !isExempt(p.getName()))
                    .toList();
            if (!remaining.isEmpty()) {
                throw new UnboundConfigurationPropertiesException(new java.util.LinkedHashSet<>(remaining));
            }
        }

        private boolean isExempt(ConfigurationPropertyName name) {
            String canonical = name.toString();
            for (String key : AppProperties.EXEMPT_UNKNOWN_KEYS) {
                if (canonical.equals(key) || canonical.startsWith(key + ".")) {
                    return true;
                }
            }
            return false;
        }
    }
}
