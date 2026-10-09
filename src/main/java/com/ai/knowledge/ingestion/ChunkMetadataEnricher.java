package com.ai.knowledge.ingestion;

import com.ai.knowledge.chunking.ChunkingStrategy;
import com.ai.knowledge.entity.KnowledgeDocument;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据打标阶段: 给分块附加检索元数据(doc_id/file_name/file_type/chunk_index/collection)。
 *
 * <p><b>跨模块契约</b>: 这些键被 rag 侧 {@code DocumentMeta}/检索展开/关键词索引读取, 增删改必须同步。
 * 父块内部键 {@code _parent_seq} 在此转为正式 {@code parent_ref}(供检索取父块)。
 * 原地修改每个 chunk 的 metadata。必须在 {@link ParentBlockAggregator} 之后执行。
 */
@Component
public class ChunkMetadataEnricher {

    /**
     * 给分块原地补检索元数据, 并把 {@code _parent_seq} 转成 {@code parent_ref}。
     *
     * @param doc    文档记录
     * @param chunks 分块列表(原地修改 metadata)
     */
    public void enrich(KnowledgeDocument doc, List<Document> chunks) {
        int index = 0;
        for (Document chunk : chunks) {
            Map<String, Object> md = new HashMap<>(chunk.getMetadata());
            Object parentSeq = md.get(ChunkingStrategy.PARENT_SEQ_KEY);
            md.remove(ChunkingStrategy.PARENT_SEQ_KEY);
            md.put("doc_id", doc.getId());
            md.put("file_name", doc.getFileName());
            md.put("file_type", doc.getFileType());
            md.put("chunk_index", index);
            md.put("collection", doc.getCollectionName());
            if (parentSeq instanceof Number n) {
                md.put("parent_ref", n.intValue()); // 父块序号(检索时按 doc_id+parent_ref 取父块文本)
            }
            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(md);
            index++;
        }
    }
}
