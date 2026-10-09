package com.ai.knowledge.ingestion;

import com.ai.common.Timeouts;
import com.ai.config.AppProperties;
import com.ai.knowledge.ParentBlockStore;
import com.ai.knowledge.chunking.ChunkedDocument;
import com.ai.knowledge.chunking.DocumentChunker;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.knowledge.parsing.TextExtractor;
import com.ai.knowledge.storage.FileStorageService;
import com.ai.rag.service.KeywordIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 文档入库编排(需求 2.2, 异步)：只做"生命周期 + 串阶段"——各处理阶段(提取/切分/父块聚合/
 * 元数据打标)委托给 {@code parsing}/{@code chunking}/{@code ingestion} 里的单一职责组件,
 * 本类不含任何切分/清洗/聚合算法。
 *
 * <p>流程: 置处理中(1) → {@link TextExtractor} 提取清洗全文 → {@link DocumentChunker} 路由切分
 * → {@link ParentBlockAggregator} 聚合父块(必须在打标前) → {@link ChunkMetadataEnricher} 打标
 * → 向量入库 → 关键词索引注册 → 置完成(2)/失败(3)。
 *
 * <p>解析(提取+切分)受 {@code app.ingestion.parse-timeout-ms} 限时; 其后各阶段异常仍由同一
 * catch 统一转 status=3 友好失败(与原实现覆盖面一致)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionOrchestrator {

    private final KnowledgeDocumentMapper documentMapper;
    private final TextExtractor textExtractor;
    private final DocumentChunker chunker;
    private final ParentBlockAggregator parentAggregator;
    private final ChunkMetadataEnricher metadataEnricher;
    private final ParentBlockStore parentBlockStore;
    private final FileStorageService fileStorageService;
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
        boolean parentsWritten = false;
        try {
            markProcessing(doc);

            // 解析限时(A2): 恶意/超复杂文档不再无限占用入库线程
            ChunkedDocument chunked = Timeouts.call(
                    () -> chunker.chunk(textExtractor.extract(doc), doc),
                    appProperties.getIngestion().getParseTimeoutMs());
            // 父块聚合必须在打标之前: 后者会把内部键 _parent_seq 转成正式 parent_ref,
            // 之后聚合已读不到键(曾因此在 markdown 用例里被"节序号==块序号"掩盖)
            Map<Integer, String> parents = chunked.producesParents()
                    ? parentAggregator.aggregate(chunked)
                    : Map.of();
            parentBlockStore.replaceParents(doc.getId(), parents); // 覆盖写; 空 Map 也清历史父块
            parentsWritten = true;
            metadataEnricher.enrich(doc, chunked.chunks());

            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new IllegalStateException("向量库不可用(未配置 Embedding/VectorStore)，无法入库");
            }
            vectorStore.add(chunked.chunks());
            // 多路召回: 关键词索引(BM25)与语义向量同步注册
            keywordIndex.addDocument(doc.getId(), doc.getFileName(), chunked.chunks());

            markDone(doc, chunked.chunks().size(), parents.size());
        } catch (Exception e) {
            // 向量化/注册失败时清理本次已写父块: 否则文档置失败(3)但父块残留 knowledge_chunk,
            // 检索 expandToParents 仍可能取到这部分内容——"失败文档却可被检索"的脏状态
            if (parentsWritten) {
                parentBlockStore.deleteByDocId(doc.getId());
                log.warn("入库失败, 已清理本次写入的父块: docId={}, err={}", doc.getId(), e.getMessage());
            }
            markFailed(doc, e);
        }
    }

    /** 置处理中(状态 0/2/3 → 1)并清空上次失败原因。 */
    private void markProcessing(KnowledgeDocument doc) {
        doc.setStatus(1);
        doc.setErrorMessage(null);
        documentMapper.updateById(doc);
    }

    /** 置完成(状态 2)+分块数; 存量行补填判重键(P3-9, 失败仅忽略不参与判重); 打完成日志。 */
    private void markDone(KnowledgeDocument doc, int chunkCount, int parentCount) {
        doc.setStatus(2);
        doc.setChunkCount(chunkCount);
        // 存量行补填判重键(P3-9): 解析阶段已读取该文件, 顺手流式计算; 唯一索引冲突
        // (同内容存量并发补填)降级为不参与判重并 WARN, 不影响入库成功状态
        if (doc.getFileHash() == null) {
            try {
                doc.setFileHash(fileStorageService.sha256Hex(doc.getStoragePath()));
            } catch (Exception e) {
                log.warn("补填 file_hash 失败(忽略, 该文档不参与判重): id={}, err={}",
                        doc.getId(), e.getMessage());
            }
        }
        documentMapper.updateById(doc);
        log.info("文档 [{}] 入库完成, 分块数={}, 父块={}", doc.getFileName(), chunkCount, parentCount);
    }

    /** 置失败(状态 3)+友好中文原因(不写原始异常/堆栈)。 */
    private void markFailed(KnowledgeDocument doc, Exception e) {
        log.error("文档 [{}] 入库失败", doc.getFileName(), e);
        doc.setStatus(3);
        doc.setErrorMessage(IngestionFailureMessages.of(e));
        documentMapper.updateById(doc);
    }
}
