package com.ai.knowledge.service;

import com.ai.knowledge.ParentBlockStore;
import com.ai.knowledge.ingestion.DocumentIngestionOrchestrator;
import com.ai.knowledge.storage.FileStorageService;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.PageResult;
import com.ai.config.AppProperties;
import com.ai.knowledge.dto.BatchUploadResultVO;
import com.ai.knowledge.dto.KnowledgeDocumentVO;
import com.ai.knowledge.dto.KnowledgeUploadVO;
import com.ai.knowledge.dto.ReindexAllVO;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.rag.service.KeywordIndex;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.user.security.RequireAdmin;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    /**
     * 明确拒绝的扩展名(最小黑名单): 归档压缩包(Tika 会提取内部条目文本, 与"不支持压缩文件"冲突)
     * 与可执行文件(无文本入库价值且上传即风险)。其余扩展名一律放行, 由魔数嗅探+入库提取兜底。
     */
    private static final Set<String> REJECTED_EXT = Set.of(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz",
            "exe", "dll", "so", "dylib", "bat", "bin", "sh");
    private static final int MAX_DELETE_SCAN = 10_000;

    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;
    private final DocumentIngestionOrchestrator ingestionService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<QdrantClient> qdrantClientProvider;
    private final SemanticAnswerCache semanticAnswerCache;
    private final AppProperties appProperties;
    private final KeywordIndex keywordIndex;
    private final ParentBlockStore parentBlockStore;

    /**
     * 上传文档：校验格式/大小/非空 → 保存文件 → 建记录(待处理) → 触发异步入库。
     * 仅管理员可执行({@link RequireAdmin})——上传即改写全公司共享的 RAG 内容，
     * 普通用户能上传就等于能往同事的答案里塞任意材料（含诱导性内容）。
     *
     * @param file   multipart 文件
     * @param userId 上传人 ID(登录态)
     * @return 上传结果(docId/fileName/status=0)
     * @throws BusinessException 文件为空/文件名/格式/大小不合法, 或非管理员(5002, HTTP 403)
     */
    @RequireAdmin
    @Transactional
    public KnowledgeUploadVO upload(MultipartFile file, Long userId) {
        // 判空: 0 字节文件(空文档)直接拒绝, 避免进入解析管线后报"解析不出有效文本"的原始错误
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_NAME_EMPTY);
        }
        String ext = FileStorageService.extensionOf(originalName);
        // 扩展名只做"明确拒绝"判定(归档/可执行文件), 不再做白名单限制——其余格式由
        // 魔数嗅探 + 入库文本提取兜底(提取不出有效文本 → status=3 友好失败)
        if (REJECTED_EXT.contains(ext)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED,
                    "不支持的文件格式 ." + ext + "(归档压缩包/可执行文件不在支持范围)");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        // 内容与扩展名一致性校验(魔数), 防伪装文件进入解析管线(P2-1)
        fileStorageService.validateContent(file, ext);

        // 内容判重(P3-9): 文件内容 SHA-256 全库判重(与文件名无关), 魔数校验之后、落盘之前
        String fileHash = fileStorageService.sha256Hex(file);
        Long dup = documentMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getFileHash, fileHash));
        if (dup != null && dup > 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_DUPLICATE,
                    "相同内容的文档已存在(按内容判重, 与文件名无关)；如需替换请先删除旧文档");
        }

        String storagePath = fileStorageService.save(file, userId);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFileName(originalName);
        doc.setFileType(ext.toUpperCase());
        doc.setFileSize(file.getSize());
        doc.setFileHash(fileHash);
        doc.setStoragePath(storagePath);
        doc.setCollectionName(appProperties.getRag().getCollectionName());
        doc.setCreatedBy(userId);
        doc.setStatus(0);
        try {
            documentMapper.insert(doc);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发竞态兜底: 唯一索引 uk_file_hash 拦截, 清理已落盘文件并给友好提示(否则冒成 5001)
            fileStorageService.delete(storagePath); // 事务将回滚, 清理已落盘文件避免孤儿
            throw new BusinessException(ErrorCode.DOCUMENT_DUPLICATE, "相同内容的文档已存在(按内容判重)");
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
     * 批量上传文档：逐文件走单文件校验与入库, **单个文件失败不影响其它文件**。
     * 每个文件的结果独立返回(成功 docId / 失败原因), 便于前端逐条提示。
     *
     * <p>必须**自己也带** {@link RequireAdmin}：本方法内部是 `this.upload(...)` 直接调用，
     * Spring AOP 不拦截自调用，只标在 {@code upload} 上会让批量入口成为绕过管理员校验的后门；
     * 且越权的 5002 会被下面的 {@code catch(BusinessException)} 降级成"每个文件都失败但 HTTP 200"，
     * 前端看不出被拒。标在本方法上才能在进入循环前就整体拒绝。
     *
     * @param files  多文件(multipart 字段名 files)
     * @param userId 上传人 ID(登录态)
     * @return 逐文件上传结果列表(顺序与入参一致)
     * @throws BusinessException 未选择任何文件, 或非管理员(5002, HTTP 403)
     */
    @RequireAdmin
    public List<BatchUploadResultVO> uploadBatch(
            MultipartFile[] files, Long userId) {
        if (files == null || files.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "未选择任何文件");
        }
        List<BatchUploadResultVO> results = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            String displayName = file == null || file.getOriginalFilename() == null
                    || file.getOriginalFilename().isBlank() ? "未命名文件" : file.getOriginalFilename();
            try {
                KnowledgeUploadVO vo = upload(file, userId);
                results.add(new BatchUploadResultVO(
                        displayName, true, vo.docId(), vo.status(), null));
            } catch (BusinessException e) {
                // 业务校验失败(空文件/格式/大小等): 记录原因, 继续处理下一文件
                log.warn("批量上传跳过文件 {}: {}", displayName, e.getMessage());
                results.add(new BatchUploadResultVO(
                        displayName, false, null, null, e.getMessage()));
            } catch (Exception e) {
                // 未预期异常: 不向上抛(避免整批失败), 记录通用提示; 完整堆栈只进日志
                log.error("批量上传单文件失败(跳过): {}", displayName, e);
                results.add(new BatchUploadResultVO(
                        displayName, false, null, null, "系统错误，请稍后重试"));
            }
        }
        return results;
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
        parentBlockStore.deleteByDocId(doc.getId()); // 父块随文档删除清理(小-大检索)
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
        resetForIngest(doc);
    }

    /**
     * 全库重建：逐篇按 reprocess 语义重灌（清旧向量/父块/关键词索引 → 异步入库）。
     *
     * <p><b>为什么需要</b>：切分策略或嵌入模型变更后，旧向量与旧切块结构不会自动更新——
     * 此前只能手工逐篇点"重新处理"，容易漏、且无记录（曾因此出现"改了切块但表现不变"的
     * 循环排查）。本入口一次性提交全部候选文档，替代手工操作。
     *
     * <p><b>语义边界</b>：① 跳过 status=1(处理中)的文档，避免与在跑的入库任务竞争；
     * ② 逐篇独立提交，单篇异常仅 WARN 跳过、不影响其余；③ 异步入库在后台进行，
     * 本方法返回的是"已提交"数——真实完成看列表页 status/chunk_count；
     * ④ 不做事务包裹（这是批处理编排，非单一原子写）：每篇的清理步骤各自幂等，
     * 且 {@code ingestAfterCommit} 在无事务时直接触发异步入库，行为与逐篇 reprocess 一致。
     *
     * @return 已提交/候选文档数
     */
    @RequireAdmin
    public ReindexAllVO reindexAll() {
        // SQL 侧排除 status=1: 避免拉取在写入库任务的 full_text 大字段; Java 侧再兜一层(正确性边界)
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .ne(KnowledgeDocument::getStatus, 1));
        int candidates = 0;
        int submitted = 0;
        for (KnowledgeDocument doc : docs) {
            if (doc.getStatus() != null && doc.getStatus() == 1) {
                continue; // 处理中的不得重置: 会与在跑的入库任务竞争同一文档
            }
            candidates++;
            try {
                resetForIngest(doc);
                submitted++;
            } catch (Exception e) {
                log.warn("全库重建: 文档 {} 提交失败, 跳过: {}", doc.getId(), e.getMessage());
            }
        }
        log.info("全库重建已提交: {}/{} 篇(异步入库中, 完成看文档列表状态)", submitted, candidates);
        return new ReindexAllVO(submitted, candidates);
    }

    /**
     * 清旧产物 + 置待处理 + 触发异步入库（reprocess 与 reindexAll 共用的单篇重置体）。
     *
     * <p>不含事务注解：由 {@link #reprocess} 的事务边界或 {@link #reindexAll} 的批处理循环负责。
     * 各步骤幂等（删不存在的向量点/父块为无操作），因此无事务时逐篇自提交也是安全的。
     *
     * @param doc 目标文档（调用方已完成状态校验）
     */
    private void resetForIngest(KnowledgeDocument doc) {
        deleteVectorPoints(doc.getId());
        keywordIndex.removeDocument(doc.getId()); // 重处理成功后再由入库流程重建
        parentBlockStore.deleteByDocId(doc.getId()); // 清旧父块, 入库成功后再重建
        if (doc.getFileHash() == null) {
            // 存量行补填判重键(P3-9): 从已存文件流式计算, 失败不阻断重处理(下轮再补)
            try {
                doc.setFileHash(fileStorageService.sha256Hex(doc.getStoragePath()));
            } catch (Exception e) {
                log.warn("重处理补填 file_hash 失败(忽略, 不参与判重): id={}, err={}",
                        doc.getId(), e.getMessage());
            }
        }
        doc.setStatus(0);
        doc.setChunkCount(0);
        doc.setErrorMessage(null);
        documentMapper.updateById(doc);
        semanticAnswerCache.evictAll(); // 知识库变更 → 语义缓存全部失效(P3-3)
        // 事务提交后再触发异步入库(与 upload 同理); 无事务时(全库重建)直接触发
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
     * <p>【仅同类内引用】唯一调用方是同类 deleteDocument/reprocess; 无外部调用点, 可降级为 private。
     *
     * @param id 文档 ID
     * @return 文档实体
     * @throws BusinessException DOCUMENT_NOT_FOUND
     */
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
        QdrantClient client = qdrantClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("Qdrant 客户端不可用, 跳过向量清理: docId={}", documentId);
            return;
        }
        try {
            var filter = Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword(
                            "doc_id", String.valueOf(documentId)))
                    .build();
            client.deleteAsync(appProperties.getRag().getCollectionName(), filter)
                    .get(15, TimeUnit.SECONDS);
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
                d.getFileSize(), d.getChunkCount(), d.getStatus(), d.getEnabled(),
                d.getErrorMessage(), d.getCreatedBy(), d.getCreatedAt());
    }

    /**
     * 禁用文档检索(管理员)：语义路与 BM25 路都不再召回该文档, 但**不删向量点/索引/文件**——
     * 启用时零成本恢复。仅 status=2(已完成)的文档可切换; 切换即失效全部语义缓存。
     *
     * @param id 文档 ID
     * @return 更新后的文档 VO
     * @throws BusinessException 文档不存在/非已完成状态, 或非管理员(5002, HTTP 403)
     */
    @RequireAdmin
    @Transactional
    public KnowledgeDocumentVO disableDocument(Long id) {
        return setRetrievalEnabled(id, false);
    }

    /**
     * 启用文档检索(管理员)：恢复语义路与 BM25 路召回, 不重新向量化。
     *
     * @param id 文档 ID
     * @return 更新后的文档 VO
     * @throws BusinessException 文档不存在/非已完成状态, 或非管理员(5002, HTTP 403)
     */
    @RequireAdmin
    @Transactional
    public KnowledgeDocumentVO enableDocument(Long id) {
        return setRetrievalEnabled(id, true);
    }

    /**
     * 启用开关的公共实现：校验状态 → 置列 → 语义缓存整体失效(禁用/启用都改变检索结果,
     * 与 upload/delete/reprocess 同款)。
     *
     * @param id      文档 ID
     * @param enabled 目标状态
     * @return 更新后的文档 VO
     */
    private KnowledgeDocumentVO setRetrievalEnabled(Long id, boolean enabled) {
        KnowledgeDocument doc = requireDocument(id);
        if (doc.getStatus() != 2) {
            // 仅"已完成"文档可切换: 待处理/处理中本来就不参与检索, 失败文档无内容可禁
            throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING);
        }
        doc.setEnabled(enabled);
        documentMapper.updateById(doc);
        semanticAnswerCache.evictAll(); // 检索结果集变化 → 语义缓存全部失效(正/负缓存一并)
        log.info("文档检索开关已切换: id={}, file={}, enabled={}", id, doc.getFileName(), enabled);
        return toVO(doc);
    }
}
