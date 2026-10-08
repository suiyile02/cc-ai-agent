package com.ai.knowledge.service;

import com.ai.knowledge.ParentBlockStore;

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
 * 读取(PDF/DOCX/TXT/MD 等, Tika) → 清洗 → 按 MIME 路由到切分策略分块 → 附加元数据
 * (doc_id/file_name/file_type/chunk_index/collection) → 语义向量化入库 →
 * 同步注册关键词索引 → 更新 knowledge_document 状态与分块数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;
    private final DocumentTextCleaner textCleaner;
    private final MimeResolver mimeResolver;
    private final ChunkingStrategyRouter chunkingRouter;
    private final ParentBlockStore parentBlockStore;
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
            ParsedResult parsed = Timeouts.call(
                    () -> parseAndSplit(doc), appProperties.getIngestion().getParseTimeoutMs());
            addMetadata(doc, parsed.chunks());
            // 父块写入(小-大检索): 策略产出父块时按 _parent_seq 聚合小块成父块文本, 存表
            if (parsed.producesParents()) {
                parentBlockStore.replaceParents(doc.getId(), aggregateParents(parsed.chunks()));
            } else {
                parentBlockStore.replaceParents(doc.getId(), Map.of()); // 覆盖清除历史父块
            }

            VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
            if (vectorStore == null) {
                throw new IllegalStateException("向量库不可用(未配置 Embedding/VectorStore)，无法入库");
            }
            vectorStore.add(parsed.chunks());
            // 多路召回: 关键词索引(BM25)与语义向量同步注册
            keywordIndex.addDocument(doc.getId(), doc.getFileName(), parsed.chunks());

            doc.setStatus(2); // 已完成
            doc.setChunkCount(parsed.chunks().size());
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
            log.info("文档 [{}] 入库完成, 分块数={}, 父块={}",
                    doc.getFileName(), parsed.chunks().size(),
                    parsed.producesParents() ? aggregateParents(parsed.chunks()).size() : 0);
        } catch (Exception e) {
            log.error("文档 [{}] 入库失败", doc.getFileName(), e);
            doc.setStatus(3); // 失败
            // 友好化失败原因(不向记录写入原始英文异常/堆栈):
            // 空文件已在上传入口拦截(FILE_EMPTY), 此处多为"非空但解析不出有效文本"
            String message = e.getMessage() == null ? "入库失败"
                    : (e.getMessage().contains("未能从文档中解析出有效文本")
                            ? "文件内容为空或无法解析出有效文本(可能为纯图片/音视频/扫描件, 或格式不受支持)"
                            : (e.getMessage().contains("疑似乱码")
                                    ? "文档内容疑似乱码(编码异常)，无法入库"
                                    : Strings.truncate(e.getMessage(), 500)));
            doc.setErrorMessage(message);
            documentMapper.updateById(doc);
        }
    }

    /**
     * 解析文档全文并分块: 清洗 → 按 MIME 路由到切分策略 → 分块。
     *
     * @param doc 文档记录(含本地存储路径)
     * @return 分块结果(小块 + 是否产出父块)
     * @throws IllegalStateException 解析不出有效文本
     */
    private ParsedResult parseAndSplit(KnowledgeDocument doc) {
        TikaDocumentReader reader = new TikaDocumentReader(
                new FileSystemResource(doc.getStoragePath()));
        List<Document> parsed = reader.get();
        // 清洗(Tika 后、切分前)
        List<Document> cleaned = textCleaner.clean(parsed, doc.getFileType());
        if (cleaned == null || cleaned.isEmpty()) {
            throw new IllegalStateException("未能从文档中解析出有效文本");
        }
        // 合并清洗后的全文为单一 Document(通常 Tika 返回 1 个; 多段时拼接收尾)
        Document full = cleaned.size() == 1 ? cleaned.get(0)
                : Document.builder()
                        .text(cleaned.stream().map(Document::getText)
                                .collect(java.util.stream.Collectors.joining("\n\n")))
                        .metadata(cleaned.get(0).getMetadata())
                        .build();
        // MIME 路由: Tika 直接探测已落盘文件; 不可判定回退扩展名推导
        String mime = mimeResolver.resolve(doc.getStoragePath(), doc.getFileType());
        ChunkingStrategy strategy = chunkingRouter.route(mime);
        List<Document> split = strategy.split(full);
        if (split == null || split.isEmpty()) {
            throw new IllegalStateException("未能从文档中解析出有效文本");
        }
        log.debug("文档 [{}] 切分: mime={}, 策略={}, 分块={}",
                doc.getFileName(), mime, strategy.name(), split.size());
        return new ParsedResult(split, strategy.producesParents());
    }

    /** 切分结果: 小块列表 + 是否产出父块 */
    private record ParsedResult(List<Document> chunks, boolean producesParents) {
    }

    /**
     * 按小块的 {@code _parent_seq} 内部键聚合父块文本: 同父块序号的小块文本拼接成一个父块。
     * 只保留确实有父块标记的小块(无标记的散落块各自成父块, 兜底不丢)。
     *
     * @param chunks 已附加元数据的小块
     * @return 父块序号 → 父块文本
     */
    private Map<Integer, String> aggregateParents(List<Document> chunks) {
        Map<Integer, StringBuilder> acc = new java.util.LinkedHashMap<>();
        for (Document chunk : chunks) {
            Object seqObj = chunk.getMetadata().get(ChunkingStrategy.PARENT_SEQ_KEY);
            int seq = seqObj instanceof Number n ? n.intValue()
                    : chunk.getMetadata().get("chunk_index") instanceof Number c ? c.intValue() : 0;
            acc.computeIfAbsent(seq, k -> new StringBuilder())
                    .append(chunk.getText()).append('\n');
        }
        Map<Integer, String> parents = new java.util.LinkedHashMap<>();
        acc.forEach((seq, sb) -> {
            String text = sb.toString().stripTrailing();
            if (!text.isEmpty()) {
                parents.put(seq, text);
            }
        });
        return parents;
    }

    /**
     * 给分块附加检索元数据(doc_id/file_name/chunk_index/collection 等)。
     * 父块内部键 {@code _parent_seq} 转为正式 {@code parent_ref}(供检索时取父块)。
     *
     * @param doc    文档记录
     * @param chunks 分块列表(原地修改 metadata)
     */
    private void addMetadata(KnowledgeDocument doc, List<Document> chunks) {
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
