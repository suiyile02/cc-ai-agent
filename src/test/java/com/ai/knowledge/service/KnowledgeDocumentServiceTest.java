package com.ai.knowledge.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.knowledge.dto.BatchUploadResultVO;
import com.ai.knowledge.dto.KnowledgeUploadVO;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeDocumentService} 上传链单元测试：空文件/空文件名/格式/大小校验,
 * 正常上传触发异步入库, 批量上传逐文件部分失败语义。
 */
class KnowledgeDocumentServiceTest {

    private KnowledgeDocumentMapper documentMapper;
    private FileStorageService fileStorageService;
    private DocumentIngestionService ingestionService;
    private SemanticAnswerCache semanticAnswerCache;
    private KeywordIndex keywordIndex;
    private KnowledgeDocumentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        fileStorageService = mock(FileStorageService.class);
        ingestionService = mock(DocumentIngestionService.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        keywordIndex = mock(KeywordIndex.class);
        service = new KnowledgeDocumentService(documentMapper, fileStorageService, ingestionService,
                mock(ObjectProvider.class), mock(ObjectProvider.class), semanticAnswerCache,
                new AppProperties(), keywordIndex);
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
}
