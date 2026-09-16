package com.ai.knowledge.service;

import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.common.Strings;
import com.ai.common.Timeouts;
import com.ai.config.AppProperties;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.rag.service.KeywordIndex;
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
 * 文档入库业务(需求 2.2, 异步)：
 * 读取(PDF/DOCX/TXT/MD, Tika) → TokenTextSplitter 分块 → 附加元数据
 * (doc_id/file_name/file_type/chunk_index/collection) → 语义向量化入库 →
 * 同步注册关键词索引 → 更新 knowledge_document 状态与分块数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 512;
    private static final int MIN_CHUNK_CHARS = 100;

    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final KeywordIndex keywordIndex;
    private final AppProperties appProperties;

    /**
     * 异步执行文档入库(ingestionExecutor 线程池, 不阻塞上传请求)。
     *
     * @param docId 知识库文档 ID；结果写回 knowledge_document(status=2/3)
     */
    @Async("ingestionExecutor")
    public void ingestDocument(Long docId) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            log.warn("入库任务取消：文档不存在 id={}", docId);
            return;
        }
        try {
            doc.setStatus(1); // 处理中
            doc.setErrorMessage(null);
            documentMapper.updateById(doc);

            // 解析限时(A2): 恶意/超复杂文档不再无限占用入库线程
            List<Document> chunks = com.ai.common.Timeouts.call(
                    () -> parseAndSplit(doc), appProperties.getIngestion().getParseTimeoutMs());
            addMetadata(doc, chunks);

            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new IllegalStateException("向量库不可用(未配置 Embedding/VectorStore)，无法入库");
            }
            vectorStore.add(chunks);
            // 多路召回: 关键词索引(BM25)与语义向量同步注册
            keywordIndex.addDocument(doc.getId(), doc.getFileName(), chunks);

            doc.setStatus(2); // 已完成
            doc.setChunkCount(chunks.size());
            documentMapper.updateById(doc);
            log.info("文档 [{}] 入库完成, 分块数={}", doc.getFileName(), chunks.size());
        } catch (Exception e) {
            log.error("文档 [{}] 入库失败", doc.getFileName(), e);
            doc.setStatus(3); // 失败
            doc.setErrorMessage(Strings.truncate(e.getMessage(), 500));
            documentMapper.updateById(doc);
        }
    }

    /**
     * 解析文档全文并分块(512 token, 单块最短 100 字符)。
     *
     * @param doc 文档记录(含本地存储路径)
     * @return 分块后的 Document 列表
     * @throws IllegalStateException 解析不出有效文本
     */
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

    /**
     * 给分块附加检索元数据(doc_id/file_name/chunk_index/collection 等)。
     *
     * @param doc    文档记录
     * @param chunks 分块列表(原地修改 metadata)
     */
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

}
