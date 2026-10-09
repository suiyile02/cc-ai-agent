package com.ai.knowledge.chunking;

import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.parsing.MimeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切分阶段: 探测 MIME → 按 {@link ChunkingStrategyRouter} 选策略 → 切分, 产出 {@link ChunkedDocument}。
 *
 * <p>输入是 {@code TextExtractor} 已清洗合并的全文 Document; 切分产出为空按"解析不出有效文本"抛异常,
 * 由入库编排层转友好失败。本类不做清洗/父块聚合/元数据打标(各归其阶段)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentChunker {

    private final MimeResolver mimeResolver;
    private final ChunkingStrategyRouter chunkingRouter;

    /**
     * 探测 MIME 并按策略切分全文。
     *
     * @param fullText {@code TextExtractor} 清洗合并后的全文
     * @param doc      文档记录(取 storagePath/fileType 探测 MIME, 取 fileName 打日志)
     * @return 切分产物(小块 + 策略父块约定)
     * @throws IllegalStateException 切分结果为空(解析不出有效文本)
     */
    public ChunkedDocument chunk(Document fullText, KnowledgeDocument doc) {
        // MIME 路由: Tika 直接探测已落盘文件; 不可判定回退扩展名推导
        String mime = mimeResolver.resolve(doc.getStoragePath(), doc.getFileType());
        ChunkingStrategy strategy = chunkingRouter.route(mime);
        List<Document> split = strategy.split(fullText);
        if (split == null || split.isEmpty()) {
            throw new IllegalStateException("未能从文档中解析出有效文本");
        }
        log.info("文档 [{}] 切分: mime={}, 策略={}, 分块={}",
                doc.getFileName(), mime, strategy.name(), split.size());
        return new ChunkedDocument(split, strategy.name(),
                strategy.producesParents(), strategy.everyChunkHasParent());
    }
}
