package com.ai.service;

import com.ai.entity.KnowledgeDocument;
import com.ai.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档入库(需求 2.2，异步)：
 * 读取(PDF/DOCX/TXT/MD, Tika) -> TokenTextSplitter 分块 -> 附加元数据(doc_id/file_name/...) ->
 * 写入向量库(Embedding 自动完成) -> 更新 knowledge_document 状态与分块数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 512;
    private static final int MIN_CHUNK_CHARS = 100;

    private final KnowledgeDocumentRepository documentRepository;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    @Async("ingestionExecutor")
    public void ingestDocument(Long docId) {
        KnowledgeDocument doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            log.warn("入库任务取消：文档不存在 id={}", docId);
            return;
        }
        try {
            doc.setStatus(1); // 处理中
            doc.setErrorMessage(null);
            documentRepository.save(doc);

            List<Document> chunks = parseAndSplit(doc);
            addMetadata(doc, chunks);

            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new IllegalStateException("向量库不可用(未配置 Embedding/VectorStore)，无法入库");
            }
            vectorStore.add(chunks);

            doc.setStatus(2); // 已完成
            doc.setChunkCount(chunks.size());
            documentRepository.save(doc);
            log.info("文档 [{}] 入库完成, 分块数={}", doc.getFileName(), chunks.size());
        } catch (Exception e) {
            log.error("文档 [{}] 入库失败", doc.getFileName(), e);
            doc.setStatus(3); // 失败
            doc.setErrorMessage(truncate(e.getMessage(), 500));
            documentRepository.save(doc);
        }
    }

    private List<Document> parseAndSplit(KnowledgeDocument doc) {
        TikaDocumentReader reader = new TikaDocumentReader(
                new FileSystemResource(doc.getStoragePath()));
        List<Document> docs = reader.get();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_CHARS)
                .build();
        List<Document> split = splitter.apply(docs);
        if (split == null || split.isEmpty()) {
            throw new IllegalStateException("未能从文档中解析出有效文本");
        }
        return split;
    }

    private void addMetadata(KnowledgeDocument doc, List<Document> chunks) {
        int index = 0;
        for (Document chunk : chunks) {
            Map<String, Object> md = new HashMap<>(chunk.getMetadata());
            md.put("doc_id", doc.getId());
            md.put("file_name", doc.getFileName());
            md.put("file_type", doc.getFileType());
            md.put("chunk_index", index);
            md.put("collection", doc.getCollectionName());
            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(md);
            index++;
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        return str.length() <= maxLength ? str : str.substring(0, maxLength);
    }
}
