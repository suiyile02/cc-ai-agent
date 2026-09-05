package com.ai.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.PageResult;
import com.ai.config.AppProperties;
import com.ai.dto.KnowledgeDocumentVO;
import com.ai.dto.KnowledgeUploadVO;
import com.ai.entity.KnowledgeDocument;
import com.ai.repository.KnowledgeDocumentRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 知识库文档管理(需求第 2 章)：上传 / 列表 / 删除 / 重新处理。
 *
 * <p>表结构与需求建表脚本 knowledge_document 对齐；文档删除时按 doc_id 过滤检索
 * 出该文档的向量点后精确删除(SimpleVectorStore / Qdrant 均支持该两步法)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 50MB
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "txt", "md");
    private static final int MAX_DELETE_SCAN = 10_000;

    private final KnowledgeDocumentRepository documentRepository;
    private final FileStorageService fileStorageService;
    private final DocumentIngestionService ingestionService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final AppProperties appProperties;

    /** 上传文档：校验 -> 保存 -> 建记录(待处理) -> 异步入库 */
    @Transactional
    public KnowledgeUploadVO upload(MultipartFile file, Long userId) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_NAME_EMPTY);
        }
        String ext = FileStorageService.extensionOf(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED,
                    "不支持的文件格式 ." + ext + "，仅支持 PDF/DOCX/TXT/MD");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        String storagePath = fileStorageService.save(file, userId);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFileName(originalName);
        doc.setFileType(ext.toUpperCase());
        doc.setFileSize(file.getSize());
        doc.setStoragePath(storagePath);
        doc.setCollectionName(appProperties.getRag().getCollectionName());
        doc.setCreatedBy(userId);
        doc.setStatus(0);
        documentRepository.save(doc);

        // 异步触发入库(不阻塞上传请求)
        ingestionService.ingestDocument(doc.getId());
        return new KnowledgeUploadVO(doc.getId(), doc.getFileName(), doc.getStatus());
    }

    public PageResult<KnowledgeDocumentVO> listDocuments(int pageNum, int pageSize,
            String fileName, Integer status, LocalDate startDate, LocalDate endDate) {
        Specification<KnowledgeDocument> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (fileName != null && !fileName.isBlank()) {
                predicates.add(cb.like(root.get("fileName"), "%" + fileName.trim() + "%"));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        startDate.atStartOfDay()));
            }
            if (endDate != null) {
                predicates.add(cb.lessThan(root.get("createdAt"),
                        endDate.plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<KnowledgeDocument> page = documentRepository.findAll(spec,
                PageRequest.of(Math.max(0, pageNum - 1), pageSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        List<KnowledgeDocumentVO> vos = page.getContent().stream().map(this::toVO).toList();
        return PageResult.from(page, vos);
    }

    /** 删除文档：清理向量 -> 删除记录 -> 删除本地文件(向量清理失败不影响主流程) */
    @Transactional
    public void deleteDocument(Long id) {
        KnowledgeDocument doc = requireDocument(id);
        if (doc.getStatus() == 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING);
        }
        deleteVectorPoints(doc.getId());
        documentRepository.deleteById(doc.getId());
        fileStorageService.delete(doc.getStoragePath());
        log.info("文档已删除: id={}, file={}", doc.getId(), doc.getFileName());
    }

    /** 重新处理：清理旧向量并重置状态后重新异步入库 */
    @Transactional
    public void reprocess(Long id) {
        KnowledgeDocument doc = requireDocument(id);
        if (doc.getStatus() == 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING);
        }
        deleteVectorPoints(doc.getId());
        doc.setStatus(0);
        doc.setChunkCount(0);
        doc.setErrorMessage(null);
        documentRepository.save(doc);
        ingestionService.ingestDocument(doc.getId());
    }

    public KnowledgeDocument requireDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * 按 metadata.doc_id 删除某文档的全部向量点。
     * 采用“过滤检索出点 id -> delete(ids)”两步法，SimpleVectorStore 与 Qdrant 均支持。
     */
    private void deleteVectorPoints(Long documentId) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return;
        }
        try {
            var filter = new FilterExpressionBuilder().eq("doc_id", documentId).build();
            SearchRequest request = SearchRequest.builder()
                    .query("doc_cleanup_" + documentId)  // 占位查询(由过滤器收敛候选集)
                    .topK(MAX_DELETE_SCAN)
                    .similarityThresholdAll()
                    .filterExpression(filter)
                    .build();
            List<Document> points = vectorStore.similaritySearch(request);
            if (points == null || points.isEmpty()) {
                return;
            }
            List<String> ids = points.stream()
                    .map(Document::getId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!ids.isEmpty()) {
                vectorStore.delete(ids);
                log.info("已清理向量 {} 条: docId={}", ids.size(), documentId);
            }
        } catch (Exception e) {
            log.warn("清理向量失败(继续后续删除流程): docId={}, err={}", documentId, e.getMessage());
        }
    }

    private KnowledgeDocumentVO toVO(KnowledgeDocument d) {
        return new KnowledgeDocumentVO(d.getId(), d.getFileName(), d.getFileType(),
                d.getFileSize(), d.getChunkCount(), d.getStatus(), d.getErrorMessage(),
                d.getCreatedBy(), d.getCreatedAt());
    }
}
