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
 * <p>{@code @ConfigurationProperties(ignoreUnknownFields = false)}(见 {@link AppProperties})会把
 * "配置里出现、但没绑到任何字段"的 {@code app.*} 键判为启动失败——能立刻暴露拼错/死配置;
 * 但有一类键天生不该有字段(如 {@code app.auth.rate-limit-backend}, 在 bean 创建<b>之前</b>就被
 * {@code @ConditionalOnProperty} 消费), 给它开字段等于造"看着能改其实没用"的假旋钮。
 *
 * <p><b>豁免安全性</b>：只放行清单点名的 unbound 记录(逐条比对, 非吞掉整个异常), 其余原样抛出——
 * 把 {@code app.rag.top-k} 写成 {@code app.rag.topk} 依然启动失败。清单本身由
 * {@code AppPropertiesBindingGuardTest} 反向校验(清单里有但实际配置没有时报"假旋钮"), 防豁免项变僵尸。
 *
 * <p>未选"改属性源枚举视图"更正统的方案: Boot 未绑定检查读的是 Environment 准备阶段 attach、
 * 按底层源实例键控缓存的枚举结果, {@code BeanFactoryPostProcessor} 再替换都晚一步; advisor 是 Boot 开放的扩展点。
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
