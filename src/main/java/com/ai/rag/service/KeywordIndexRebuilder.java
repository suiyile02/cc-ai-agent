package com.ai.rag.service;

import com.ai.config.AppProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.document.Document;

/**
 * 关键词索引启动自动重建：应用就绪后从 Qdrant 滚动读取全部分块(payload 含原文),
 * 按文档重新注册进 {@link KeywordIndex}——不调用 Embedding、不重写向量, 秒级完成。
 *
 * <p>解决已知限制: BM25 索引此前只存进程内存, 重启即空, 需手工逐文档 reprocess。
 * 设计: 只在索引为空时重建(防御热重启重复构建); Qdrant 不可用时告警跳过(语义检索不受影响);
 * 后台线程执行, 不阻塞应用启动; 重建期间检索可用但结果逐步增多(最终一致)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordIndexRebuilder {

    private static final int PAGE_SIZE = 1000;

    private final ObjectProvider<QdrantClient> qdrantClientProvider;
    private final KeywordIndex keywordIndex;
    private final AppProperties appProperties;

    /**
     * 应用就绪后触发重建(异步线程, 不阻塞启动)。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (!appProperties.getRag().isAutoRebuildIndex()) {
            return;
        }
        Thread worker = new Thread(this::rebuild, "kw-index-rebuild");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 执行重建: 滚动读取 Qdrant 全部分块 → 按文档分组 → 注册关键词索引。
     */
    void rebuild() {
        QdrantClient client = qdrantClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("关键词索引重建跳过: Qdrant 客户端不可用(语义检索不受影响)");
            return;
        }
        if (!keywordIndex.isEmpty()) {
            log.info("关键词索引重建跳过: 索引非空(共 {} 块)", keywordIndex.size());
            return;
        }
        long start = System.currentTimeMillis();
        try {
            Map<Long, String> docNames = new LinkedHashMap<>();
            Map<Long, List<Document>> chunksByDoc = new LinkedHashMap<>();
            io.qdrant.client.grpc.Common.PointId offset = null;
            int total = 0;
            do {
                Points.ScrollPoints.Builder request = Points.ScrollPoints.newBuilder()
                        .setCollectionName(appProperties.getRag().getCollectionName())
                        .setLimit(PAGE_SIZE)
                        .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
                if (offset != null) {
                    request.setOffset(offset);
                }
                Points.ScrollResponse resp = client.scrollAsync(request.build())
                        .get(30, TimeUnit.SECONDS);
                for (Points.RetrievedPoint point : resp.getResultList()) {
                    Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
                    String text = stringValue(payload.get("content"));
                    Long docId = longValue(payload.get("doc_id"));
                    String fileName = stringValue(payload.get("file_name"));
                    Integer chunkIndex = intValue(payload.get("chunk_index"));
                    if (text == null || text.isBlank() || docId == null) {
                        continue;
                    }
                    docNames.putIfAbsent(docId, fileName == null ? ("doc-" + docId) : fileName);
                    Document doc = Document.builder()
                            .text(text)
                            .metadata(Map.of("doc_id", docId, "file_name", fileName == null ? "" : fileName,
                                    "chunk_index", chunkIndex == null ? 0 : chunkIndex))
                            .build();
                    chunksByDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(doc);
                    total++;
                }
                offset = resp.hasNextPageOffset() ? resp.getNextPageOffset() : null;
            } while (offset != null);

            chunksByDoc.forEach((docId, chunks) ->
                    keywordIndex.addDocument(docId, docNames.get(docId), chunks));
            log.info("关键词索引自动重建完成: 文档 {} 个, 分块 {} 个, 耗时 {}ms",
                    chunksByDoc.size(), total, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("关键词索引自动重建失败(关键词检索路不可用, 语义检索不受影响): {}", e.getMessage());
        }
    }

    /** payload 字符串值(空安全) */
    private static String stringValue(JsonWithInt.Value value) {
        if (value == null || !value.hasStringValue()) {
            return value != null && !value.getStringValue().isEmpty() ? value.getStringValue() : null;
        }
        return value.getStringValue();
    }

    /** payload 长整型值(兼容字符串形式) */
    private static Long longValue(JsonWithInt.Value value) {
        if (value == null) {
            return null;
        }
        if (value.hasIntegerValue()) {
            return value.getIntegerValue();
        }
        try {
            return Long.parseLong(value.getStringValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** payload 整型值(兼容字符串形式) */
    private static Integer intValue(JsonWithInt.Value value) {
        if (value == null) {
            return null;
        }
        if (value.hasIntegerValue()) {
            return (int) value.getIntegerValue();
        }
        try {
            return Integer.parseInt(value.getStringValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
