package com.ai.knowledge.parsing;

import com.ai.knowledge.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文本提取阶段: Tika 读取已落盘文件 → {@link DocumentTextCleaner} 清洗 → 合并为单一全文 Document,
 * 交给 {@code DocumentChunker} 切分。
 *
 * <p>清洗后为空(或 Tika 提取不出文本)按"解析不出有效文本"抛异常, 由入库编排层转友好失败。
 * 本类不做切分/MIME 路由/父块聚合/元数据打标(各归其阶段)。
 */
@Component
@RequiredArgsConstructor
public class TextExtractor {

    private final DocumentTextCleaner textCleaner;

    /**
     * 读取并清洗文档全文, 合并为单一 Document。
     *
     * @param doc 文档记录(含本地存储路径与 fileType)
     * @return 清洗合并后的全文(单 Document)
     * @throws IllegalStateException 解析不出有效文本
     */
    public Document extract(KnowledgeDocument doc) {
        TikaDocumentReader reader = new TikaDocumentReader(
                new FileSystemResource(doc.getStoragePath()));
        List<Document> parsed = reader.get();
        // 清洗(Tika 后、切分前)
        List<Document> cleaned = textCleaner.clean(parsed, doc.getFileType());
        if (cleaned == null || cleaned.isEmpty()) {
            throw new IllegalStateException("未能从文档中解析出有效文本");
        }
        // 合并清洗后的全文为单一 Document(通常 Tika 返回 1 个; 多段时拼接收尾)
        return cleaned.size() == 1 ? cleaned.get(0)
                : Document.builder()
                        .text(cleaned.stream().map(Document::getText)
                                .collect(Collectors.joining("\n\n")))
                        .metadata(cleaned.get(0).getMetadata())
                        .build();
    }
}
