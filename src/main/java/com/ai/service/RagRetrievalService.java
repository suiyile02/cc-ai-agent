package com.ai.service;

import com.ai.config.AppProperties;
import com.ai.dto.SourceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索服务(需求 3.3)：similaritySearch + 相似度阈值过滤, 命中结果供上下文注入与来源展示。
 *
 * <p>说明：本实现采用“先检索、再注入”的手动 RAG 流程(Spring AI 2.0 中 QuestionAnswerAdvisor
 * 已迁移至 {@code org.springframework.ai.chat.client.advisor.vectorstore} 并配合新的
 * retrieval API；手动流程便于返回 sources 并做阈值/日志控制)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final AppProperties appProperties;

    public boolean available() {
        return vectorStoreProvider.getIfAvailable() != null;
    }

    /** 检索最相关的文档块(已按相似度阈值过滤)。向量库不可用时返回空列表并降级。 */
    public List<Document> retrieve(String query) {
        return retrieve(query, appProperties.getRag().getTopK(),
                appProperties.getRag().getSimilarityThreshold());
    }

    public List<Document> retrieve(String query, int topK, double similarityThreshold) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("向量库不可用(Embedding/VectorStore 未配置)，RAG 降级为不注入上下文");
            return List.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.warn("RAG 检索失败，降级为空上下文: {}", e.getMessage());
            return List.of();
        }
    }

    /** 组装注入 LLM 的上下文文本 */
    public String buildContext(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        int max = appProperties.getRag().getContextMaxChars();
        for (Document doc : hits) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append('[').append(index++).append("] (来源: ").append(fileNameOf(doc)).append(")\n");
            sb.append(text).append("\n\n");
            if (sb.length() > max) {
                sb.setLength(max);
                sb.append("\n...(上下文超长截断)");
                break;
            }
        }
        return sb.toString().trim();
    }

    /** 命中文档 → 来源 VO(供响应与日志) */
    public List<SourceVO> toSources(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<SourceVO> sources = new ArrayList<>(hits.size());
        for (Document doc : hits) {
            sources.add(new SourceVO(
                    fileNameOf(doc),
                    docIdOf(doc),
                    chunkIndexOf(doc),
                    snippetOf(doc),
                    scoreOf(doc)));
        }
        return sources;
    }

    private String fileNameOf(Document doc) {
        Object v = doc.getMetadata().get("file_name");
        return v == null ? "未知来源" : String.valueOf(v);
    }

    private Long docIdOf(Document doc) {
        Object v = doc.getMetadata().get("doc_id");
        return v instanceof Number n ? n.longValue() : null;
    }

    private Integer chunkIndexOf(Document doc) {
        Object v = doc.getMetadata().get("chunk_index");
        return v instanceof Number n ? n.intValue() : null;
    }

    private Double scoreOf(Document doc) {
        Double score = doc.getScore();
        if (score != null) {
            return score;
        }
        // 部分实现把距离放在 metadata.distance, cosine 相似度 ≈ 1 - distance
        Map<String, Object> md = doc.getMetadata();
        Object dist = md.get("distance");
        if (dist instanceof Number d) {
            double sim = 1.0 - d.doubleValue();
            return Math.max(0.0, Math.min(1.0, sim));
        }
        return null;
    }

    private String snippetOf(Document doc) {
        String text = doc.getText();
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
