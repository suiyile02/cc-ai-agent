package com.ai.knowledge.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.PageResult;
import com.ai.config.AppProperties;
import com.ai.knowledge.dto.KnowledgeDocumentVO;
import com.ai.knowledge.dto.KnowledgeUploadVO;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.rag.service.KeywordIndex;
import com.ai.user.security.RequireAdmin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 知识库文档管理业务(需求第 2 章)：上传 / 列表 / 删除 / 重新处理。
 *
 * <p>数据访问基于 MyBatis-Plus(KnowledgeDocumentMapper)；删除文档时按 metadata.doc_id
 * 过滤检索出向量点后精确清理(Qdrant 支持过滤+按点删两步法)，并同步清理
 * 关键词召回索引({@link KeywordIndex})。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 50MB
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "txt", "md");
    private static final int MAX_DELETE_SCAN = 10_000;

    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;
    private final DocumentIngestionService ingestionService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<io.qdrant.client.QdrantClient> qdrantClientProvider;
    private final com.ai.rag.service.SemanticAnswerCache semanticAnswerCache;
    private final AppProperties appProperties;
    private final KeywordIndex keywordIndex;

    /**
     * 上传文档：校验格式/大小 → 保存文件 → 建记录(待处理) → 触发异步入库。
     *
     * @param file   multipart 文件
     * @param userId 上传人 ID(登录态)
     * @return 上传结果(docId/fileName/status=0)
     * @throws BusinessException 文件名/格式/大小不合法
     */
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
        // 内容与扩展名一致性校验(魔数), 防伪装文件进入解析管线(P2-1)
        fileStorageService.validateContent(file, ext);

        String storagePath = fileStorageService.save(file, userId);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFileName(originalName);
        doc.setFileType(ext.toUpperCase());
        doc.setFileSize(file.getSize());
        doc.setStoragePath(storagePath);
        doc.setCollectionName(appProperties.getRag().getCollectionName());
        doc.setCreatedBy(userId);
        doc.setStatus(0);
        try {
            documentMapper.insert(doc);
        } catch (RuntimeException e) {
            fileStorageService.delete(storagePath); // 事务将回滚, 清理已落盘文件避免孤儿
            throw e;
        }
        // 事务提交后再触发异步入库(避免异步任务先于 commit 执行、查不到未提交记录)
        semanticAnswerCache.evictAll(); // 知识库变更 → 语义缓存全部失效(P3-3)
        ingestAfterCommit(doc.getId());
        return new KnowledgeUploadVO(doc.getId(), doc.getFileName(), doc.getStatus());
    }

    /**
     * 文档分页查询(文件名模糊/状态/创建时间区间过滤)。
     *
     * @param pageNum   页码(从 1 开始)
     * @param pageSize  每页条数
     * @param fileName  文件名模糊匹配(可选)
     * @param status    状态过滤(可选)
     * @param startDate 创建时间起(可选)
     * @param endDate   创建时间止(可选)
     * @return 分页文档 VO
     */
    public PageResult<KnowledgeDocumentVO> listDocuments(int pageNum, int pageSize,
            String fileName, Integer status, LocalDate startDate, LocalDate endDate) {
        Page<KnowledgeDocument> mpPage = new Page<>(pageNum, pageSize);
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<KnowledgeDocument> qw = new LambdaQueryWrapper<>();
        qw.like(StringUtils.hasText(fileName), KnowledgeDocument::getFileName, fileName)
                .eq(status != null, KnowledgeDocument::getStatus, status)
                .ge(startDate != null, KnowledgeDocument::getCreatedAt, start)
                .lt(endDate != null, KnowledgeDocument::getCreatedAt, end)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        documentMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentVO> vos = mpPage.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(vos, mpPage.getTotal(), (int) mpPage.getCurrent(),
                (int) mpPage.getSize());
    }

    /**
     * 删除文档：清理向量与关键词索引 → 删除记录 → 删除本地文件(向量清理失败不影响主流程)。
     * 仅管理员可执行({@link RequireAdmin})——删除影响全公司共享的 RAG 内容。
     *
     * @param id 文档 ID
     * @throws BusinessException 文档不存在/正在处理中, 或非管理员(5002, HTTP 403)
     */
    @RequireAdmin
    @Transactional
    public void deleteDocument(Long id) {
        KnowledgeDocument doc = requireDocument(id);
        if (doc.getStatus() == 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING);
        }
        deleteVectorPoints(doc.getId());
        keywordIndex.removeDocument(doc.getId());
        documentMapper.deleteById(doc.getId());
        fileStorageService.delete(doc.getStoragePath());
        semanticAnswerCache.evictAll(); // 知识库变更 → 语义缓存全部失效(P3-3)
        log.info("文档已删除: id={}, file={}", doc.getId(), doc.getFileName());
    }

    /**
     * 重新处理文档：清理旧向量/关键词索引并重置为待处理, 再重新异步入库。
     *
     * @param id 文档 ID
     * @throws BusinessException 文档不存在或正在处理中
     */
    @RequireAdmin
    @Transactional
    public void reprocess(Long id) {
        KnowledgeDocument doc = requireDocument(id);
        if (doc.getStatus() == 1) {
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING);
        }
        deleteVectorPoints(doc.getId());
        keywordIndex.removeDocument(doc.getId()); // 重处理成功后再由入库流程重建
        doc.setStatus(0);
        doc.setChunkCount(0);
        doc.setErrorMessage(null);
        documentMapper.updateById(doc);
        semanticAnswerCache.evictAll(); // 知识库变更 → 语义缓存全部失效(P3-3)
        // 事务提交后再触发异步入库(与 upload 同理)
        ingestAfterCommit(doc.getId());
    }

    /**
     * 注册事务提交后回调再触发异步入库：@Async 任务提交若发生在 @Transactional 事务内,
     * 可能在 commit 前开始执行, selectById 查不到未提交的文档记录而静默取消。
     * 无活动事务时(理论上不会发生)直接提交, 保证行为不回退。
     *
     * @param docId 文档 ID
     */
    private void ingestAfterCommit(Long docId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ingestionService.ingestDocument(docId);
                }
            });
        } else {
            ingestionService.ingestDocument(docId);
        }
    }

    /**
     * 按 ID 获取文档, 不存在抛业务异常。
     *
     * @param id 文档 ID
     * @return 文档实体
     * @throws BusinessException DOCUMENT_NOT_FOUND
     */
    /** 【仅同类内引用】唯一调用方是同类 deleteDocument/reprocess; 无外部调用点, 可降级为 private。 */
    public KnowledgeDocument requireDocument(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return doc;
    }

    /**
     * 按 metadata.doc_id 删除文档全部向量点。
     *
     * <p>【已被替代】原"similaritySearch 扫描最多 1 万点再按点删"的两步法已替换为
     * Qdrant 原生 filter delete(一步完成, O(匹配点) 而非 O(扫描上限))。
     * 注意 payload 中 doc_id 以字符串存储, 过滤值必须 {@code String.valueOf(documentId)}。
     *
     * @param documentId 文档 ID
     */
    private void deleteVectorPoints(Long documentId) {
        io.qdrant.client.QdrantClient client = qdrantClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("Qdrant 客户端不可用, 跳过向量清理: docId={}", documentId);
            return;
        }
        try {
            var filter = io.qdrant.client.grpc.Common.Filter.newBuilder()
                    .addMust(io.qdrant.client.ConditionFactory.matchKeyword(
                            "doc_id", String.valueOf(documentId)))
                    .build();
            client.deleteAsync(appProperties.getRag().getCollectionName(), filter)
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);
            log.info("已按过滤条件清理向量: docId={}", documentId);
        } catch (Exception e) {
            log.warn("清理向量失败(继续后续删除流程): docId={}, err={}", documentId, e.getMessage());
        }
    }

    /**
     * 实体 → 列表 VO(禁止把实体直接暴露给前端)。
     *
     * @param d 文档实体
     * @return VO
     */
    private KnowledgeDocumentVO toVO(KnowledgeDocument d) {
        return new KnowledgeDocumentVO(d.getId(), d.getFileName(), d.getFileType(),
                d.getFileSize(), d.getChunkCount(), d.getStatus(), d.getErrorMessage(),
                d.getCreatedBy(), d.getCreatedAt());
    }
}
