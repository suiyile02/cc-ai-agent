package com.ai.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入分批策略: 按<b>条数</b>分批(每批 ≤ {@link #MAX_BATCH_SIZE} 条), 而非 Spring AI 默认的
 * TokenCountBatchingStrategy(按 token 数 8191 上限分批)。
 *
 * <p><b>为什么必须覆盖默认</b>: DashScope 的 OpenAI 兼容 embedding 接口单次请求最多 20 条输入,
 * 而默认策略只看 token——一个代码/长文档切成几十个小块时 token 总量远不到 8191, 整批一次发过去,
 * 接口返回 400 {@code batch size is invalid, it should not be larger than 20}, 整篇文档入库失败。
 *
 * <p>装配: Spring AI 的 QdrantVectorStoreAutoConfiguration 里
 * {@code batchingStrategy()} 是 {@code @Bean @ConditionalOnMissingBean}, 本项目声明同类型
 * Bean 即覆盖默认(默认值单一来源=Java 约定: 常量写在此类, 不在 yaml 重复)。
 */
public class CountBatchingStrategy implements BatchingStrategy {

    /** DashScope OpenAI 兼容 embedding 接口的单次条数上限 */
    static final int MAX_BATCH_SIZE = 20;

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> batches = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return batches;
        }
        for (int i = 0; i < documents.size(); i += MAX_BATCH_SIZE) {
            batches.add(List.copyOf(documents.subList(i, Math.min(documents.size(), i + MAX_BATCH_SIZE))));
        }
        return batches;
    }
}
