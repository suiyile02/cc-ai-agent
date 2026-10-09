package com.ai.knowledge.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.knowledge.dto.BatchUploadResultVO;
import com.ai.knowledge.ParentBlockStore;
import com.ai.knowledge.dto.KnowledgeDocumentVO;
import com.ai.knowledge.dto.KnowledgeUploadVO;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.ingestion.DocumentIngestionOrchestrator;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.knowledge.storage.FileStorageService;
import com.ai.rag.service.KeywordIndex;
import com.ai.rag.service.SemanticAnswerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeDocumentService} 上传链单元测试：空文件/空文件名/格式/大小校验,
 * 正常上传触发异步入库, 批量上传逐文件部分失败语义。
 */
class KnowledgeDocumentServiceTest {

    private KnowledgeDocumentMapper documentMapper;
    private FileStorageService fileStorageService;
    private DocumentIngestionOrchestrator ingestionService;
    private SemanticAnswerCache semanticAnswerCache;
    private KeywordIndex keywordIndex;
    private ParentBlockStore parentBlockStore;
    private KnowledgeDocumentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        fileStorageService = mock(FileStorageService.class);
        ingestionService = mock(DocumentIngestionOrchestrator.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        keywordIndex = mock(KeywordIndex.class);
        parentBlockStore = mock(ParentBlockStore.class);
        service = new KnowledgeDocumentService(documentMapper, fileStorageService, ingestionService,
                mock(ObjectProvider.class), mock(ObjectProvider.class), semanticAnswerCache,
                new AppProperties(), keywordIndex, parentBlockStore);
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown",
                content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void emptyFileRejectedWith1005() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(file("空文件.txt", null), 1L));
        assertEquals(ErrorCode.FILE_EMPTY, e.getErrorCode());
    }

    @Test
    void rejectedExtArchiveRejectedWith1002() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(file("压缩包.zip", "文本内容"), 1L));
        assertEquals(ErrorCode.FILE_TYPE_NOT_SUPPORTED, e.getErrorCode());
        verify(documentMapper, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void nonWhitelistedTextExtNowAccepted() throws Exception {
        // 去白名单: .java 等文本扩展名放行(魔数/提取兜底), 不再被扩展名白名单拒绝
        when(fileStorageService.sha256Hex(any(MultipartFile.class))).thenReturn("e".repeat(64));
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(fileStorageService.save(any(MultipartFile.class), any())).thenReturn("/tmp/a.java");

        service.upload(file("Utils.java", "public class Utils {}"), 1L);

        verify(documentMapper).insert(any(KnowledgeDocument.class));
    }

    @Test
    void emptyFileNameRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(file("", "内容"), 1L));
        assertEquals(ErrorCode.FILE_NAME_EMPTY, e.getErrorCode());
    }

    @Test
    void unsupportedExtensionRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(file("脚本.exe", "内容"), 1L));
        assertEquals(ErrorCode.FILE_TYPE_NOT_SUPPORTED, e.getErrorCode());
    }

    @Test
    void oversizeFileRejected() {
        MultipartFile big = mock(MultipartFile.class);
        when(big.isEmpty()).thenReturn(false);
        when(big.getOriginalFilename()).thenReturn("大文件.pdf");
        when(big.getSize()).thenReturn(51L * 1024 * 1024);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(big, 1L));
        assertEquals(ErrorCode.FILE_TOO_LARGE, e.getErrorCode());
    }

    @Test
    void normalUploadReturnsVoAndTriggersIngestion() {
        when(fileStorageService.save(any(), anyLong())).thenReturn("D:/files/a.md");
        // insert 回填主键, 保证 ingestDocument(docId) 以真实 id 触发
        when(documentMapper.insert(any(com.ai.knowledge.entity.KnowledgeDocument.class)))
                .thenAnswer(inv -> {
                    com.ai.knowledge.entity.KnowledgeDocument doc = inv.getArgument(0);
                    doc.setId(88L);
                    return 1;
                });

        KnowledgeUploadVO vo = service.upload(file("制度.md", "# 测试制度\n内容"), 1L);

        assertEquals("制度.md", vo.fileName());
        assertEquals(0, vo.status());
        verify(documentMapper).insert(any(com.ai.knowledge.entity.KnowledgeDocument.class));
        verify(ingestionService).ingestDocument(anyLong()); // 无事务环境直接触发入库
        verify(semanticAnswerCache).evictAll(); // 知识库变更 → 语义缓存失效
    }

    @Test
    void uploadBatchPartialFailureContinues() {
        when(fileStorageService.save(any(), anyLong())).thenReturn("D:/files/b.md");
        // insert 回填主键, 保证 ingestDocument(docId) 以真实 id 触发
        when(documentMapper.insert(any(com.ai.knowledge.entity.KnowledgeDocument.class)))
                .thenAnswer(inv -> {
                    com.ai.knowledge.entity.KnowledgeDocument doc = inv.getArgument(0);
                    doc.setId(88L);
                    return 1;
                });

        MockMultipartFile empty = file("空文件.txt", null);
        MockMultipartFile good = file("好文档.md", "# 内容");
        List<BatchUploadResultVO> results = service.uploadBatch(
                new MultipartFile[]{empty, good}, 1L);

        assertEquals(2, results.size());
        assertFalse(results.get(0).success());
        assertNull(results.get(0).docId());
        assertTrue(results.get(0).message().contains("文件内容为空"));
        assertTrue(results.get(1).success());
        assertEquals("好文档.md", results.get(1).fileName());
        assertEquals(0, results.get(1).status());
        verify(ingestionService).ingestDocument(anyLong()); // 仅成功文件触发入库
    }

    @Test
    void uploadBatchEmptyArrayRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.uploadBatch(new MultipartFile[0], 1L));
        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
    }

    /* ---------------- 检索启用/禁用开关 ---------------- */

    private KnowledgeDocument completedDoc(long id) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setFileName("员工手册.md");
        doc.setStatus(2);
        doc.setEnabled(true);
        return doc;
    }

    @Test
    void disableSetsFlagAndEvictsSemanticCache() {
        when(documentMapper.selectById(9L)).thenReturn(completedDoc(9L));

        KnowledgeDocumentVO vo = service.disableDocument(9L);

        assertEquals(Boolean.FALSE, vo.enabled());
        verify(documentMapper).updateById(any(KnowledgeDocument.class));
        verify(semanticAnswerCache).evictAll();
    }

    @Test
    void enableRestoresFlagAndEvictsSemanticCache() {
        KnowledgeDocument doc = completedDoc(9L);
        doc.setEnabled(false);
        when(documentMapper.selectById(9L)).thenReturn(doc);

        KnowledgeDocumentVO vo = service.enableDocument(9L);

        assertEquals(Boolean.TRUE, vo.enabled());
        verify(semanticAnswerCache).evictAll();
    }

    @Test
    void toggleRejectedForNonCompletedDoc() {
        KnowledgeDocument doc = completedDoc(9L);
        doc.setStatus(0); // 待处理: 本就不参与检索, 切换无意义
        when(documentMapper.selectById(9L)).thenReturn(doc);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.disableDocument(9L));
        assertEquals(ErrorCode.DOCUMENT_PROCESSING, e.getErrorCode());
        verify(semanticAnswerCache, never()).evictAll();
        verify(documentMapper, never()).updateById(any(KnowledgeDocument.class));
    }

    @Test
    void toggleMissingDocFailsNotFound() {
        when(documentMapper.selectById(9L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.enableDocument(9L));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, e.getErrorCode());
        verify(semanticAnswerCache, never()).evictAll();
    }

    /* ---------------- 父子文档(小-大检索): 父块随删除/重处理清理 ---------------- */

    @Test
    void deleteDocumentClearsParentBlocksAlongWithIndexes() {
        KnowledgeDocument d = completedDoc(5L);
        d.setStoragePath("./data/files/x.md");
        when(documentMapper.selectById(5L)).thenReturn(d);

        service.deleteDocument(5L);

        verify(parentBlockStore).deleteByDocId(5L); // 留存的父块不得在文档删除后继续可注入
        verify(keywordIndex).removeDocument(5L);
        verify(documentMapper).deleteById(5L);
        verify(fileStorageService).delete("./data/files/x.md");
        verify(semanticAnswerCache).evictAll();
    }

    @Test
    void reprocessClearsOldParentsBeforeRebuild() {
        KnowledgeDocument d = completedDoc(6L);
        d.setFileHash("hash-x"); // 跳过重处理的补填判重键分支
        when(documentMapper.selectById(6L)).thenReturn(d);

        service.reprocess(6L);

        verify(parentBlockStore).deleteByDocId(6L); // 清旧父块, 入库成功后再重建
        verify(keywordIndex).removeDocument(6L);
        assertEquals(0, d.getStatus());
        assertEquals(0, d.getChunkCount());
        verify(ingestionService).ingestDocument(6L); // 无活动事务 → 直接触发异步入库
    }
}