package com.ai.knowledge;

import java.util.Set;

/**
 * 文档检索可见性契约：提供当前"已禁用检索"的文档 ID 集合, 供检索模块(rag)在
 * 语义路与 BM25 路排除禁用文档。
 *
 * <p>唯一事实源是 {@code knowledge_document.enabled} 列(0=禁用); 本契约每次调用实时查询
 * (主键索引极快), 保证管理员切换后下一次检索立即生效, 不引入缓存一致性负担。
 * 数据(文档)归 knowledge 模块所有, 故契约定义在生产方根包(AGENTS.md 规则 5)。
 */
public interface DocumentVisibility {

    /**
     * 当前已禁用检索的文档 ID 集合。
     *
     * @return 禁用文档 ID 集合; 无禁用文档返回空集; 查询异常返回空集(降级:
     *         禁用过滤失效可能使禁用文档被检出, WARN 可见, 绝不阻断检索)
     */
    Set<Long> disabledDocIds();
}
