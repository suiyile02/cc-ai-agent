package com.ai.knowledge.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.knowledge.dto.BatchUploadResultVO;
import com.ai.knowledge.dto.KnowledgeDocumentVO;
import com.ai.knowledge.dto.KnowledgeUploadVO;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.rag.service.KeywordIndex;
import com.ai.rag.service.SemanticAnswerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeDocumentService} 内容判重(P3-9)单元测试：
 * 上传前置查重(命中即拒, 不落盘不落库)、并发竞态的唯一索引翻译
 * (DuplicateKeyException → DOCUMENT_DUPLICATE + 清理已落盘文件)、哈希存储。
 */
class KnowledgeDocumentDedupTest {

    private KnowledgeDocumentMapper documentMapper;
    private FileStorageService fileStorageService;
    private SemanticAnswerCache semanticAnswerCache;
    private KnowledgeDocumentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        fileStorageService = mock(FileStorageService.class);
        var ingestion = mock(DocumentIngestionService.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        ObjectProvider<VectorStore> vs = mock(ObjectProvider.class);
        when(vs.getIfAvailable()).thenReturn(mock(VectorStore.class));
        service = new KnowledgeDocumentService(documentMapper, fileStorageService, ingestion,
                vs, mock(ObjectProvider.class), semanticAnswerCache,
                new AppProperties(), mock(KeywordIndex.class));
    }

    private MultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void duplicateContentRejectedBeforeSaving() {
        when(fileStorageService.sha256Hex(any(MultipartFile.class))).thenReturn("a".repeat(64));
        when(documentMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(file("重复文档.md", "相同内容"), 1L));

        assertEquals(ErrorCode.DOCUMENT_DUPLICATE, e.getErrorCode());
        verify(fileStorageService, never()).save(any(MultipartFile.class), any());
        verify(documentMapper, never()).insert(any(KnowledgeDocument.class));
        verify(semanticAnswerCache, never()).evictAll();
    }

    @Test
    void concurrentDuplicateInsertTranslatedWithFileCleanup() {
        when(fileStorageService.sha256Hex(any(MultipartFile.class))).thenReturn("b".repeat(64));
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(fileStorageService.save(any(MultipartFile.class), any())).thenReturn("/tmp/f1");
        // 唯一索引 uk_file_hash 拦截并发竞态
        when(documentMapper.insert(any(KnowledgeDocument.class)))
                .thenThrow(new DuplicateKeyException("uk_file_hash"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.upload(file("并发重复.md", "相同内容"), 1L));

        assertEquals(ErrorCode.DOCUMENT_DUPLICATE, e.getErrorCode(),
                "唯一索引竞态必须翻译为友好业务码, 不得冒成 5001");
        verify(fileStorageService).delete("/tmp/f1");
        verify(semanticAnswerCache, never()).evictAll();
    }

    @Test
    void successfulUploadStoresContentHash() throws Exception {
        when(fileStorageService.sha256Hex(any(MultipartFile.class))).thenReturn("c".repeat(64));
        when(fileStorageService.save(any(MultipartFile.class), any())).thenReturn("/tmp/f2");
        var captor = ArgumentCaptor.forClass(KnowledgeDocument.class);

        service.upload(file("新文档.md", "全新内容"), 1L);

        org.mockito.Mockito.verify(documentMapper).insert(captor.capture());
        // 判重键来自 fileStorageService.sha256Hex 的返回(stub 为 c×64)
        assertEquals("c".repeat(64), captor.getValue().getFileHash(),
                "入库记录必须携带内容 SHA-256(判重键)");
    }

    @Test
    void batchUploadSurfacesDuplicatePerFile() {
        when(fileStorageService.sha256Hex(any(MultipartFile.class))).thenReturn("d".repeat(64));
        when(documentMapper.selectCount(any())).thenReturn(1L);

        List<BatchUploadResultVO> results = service.uploadBatch(
                new MultipartFile[]{file("重复1.md", "相同内容"), file("重复2.md", "相同内容")}, 1L);

        assertEquals(2, results.size());
        assertFalse(results.get(0).success());
        assertFalse(results.get(1).success());
        assertTrue(results.get(0).message().contains("已存在"),
                "批量通道逐文件给出判重失败原因");
        verify(fileStorageService, never()).save(any(MultipartFile.class), any());
    }
}
