package com.ai.knowledge.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileStorageService.validateContent} 内容嗅探单元测试(2026-10-07 去白名单改造):
 * 校验不再按扩展名分派, 而是按魔数——PDF/ZIP 容器放行、文本类拒绝 NUL 伪装、
 * 归档/可执行文件由上传层 REJECTED_EXT 提前拒绝。
 */
class FileStorageValidateContentTest {

    private final FileStorageService service =
            new FileStorageService(new AppProperties());

    private static MultipartFile bytes(String ext, byte[] content) {
        return new MockMultipartFile("file", "测试." + ext, "application/octet-stream", content);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    @Test
    void pdfHeaderPassesRegardlessOfExt() {
        assertDoesNotThrow(() -> service.validateContent(bytes("pdf", ascii("%PDF-1.7 ...")), "pdf"));
        // 伪造成 txt 但内容是 PDF: 按内容嗅探放行(Tika 提取 PDF 文本)
        assertDoesNotThrow(() -> service.validateContent(bytes("txt", ascii("%PDF-1.7 ...")), "txt"));
    }

    @Test
    void zipContainerPassesBombCheckRegardlessOfExt() {
        // xlsx/pptx 等 ZIP 容器: 走 checkZipBomb, 不再因 default 分支被 binaryHead 误杀
        assertDoesNotThrow(() -> service.validateContent(
                bytes("xlsx", new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00}), "xlsx"));
        assertDoesNotThrow(() -> service.validateContent(
                bytes("docx", new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00}), "docx"));
    }

    @Test
    void binaryWithNulRejectedAsTextDisguise() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.validateContent(bytes("py", new byte[]{0x00, 0x01, 0x02}), "py"));
        assertEquals(ErrorCode.FILE_TYPE_NOT_SUPPORTED, e.getErrorCode());
    }

    @Test
    void plainTextPasses() {
        assertDoesNotThrow(() -> service.validateContent(bytes("java", ascii("public class A {}")), "java"));
        assertDoesNotThrow(() -> service.validateContent(bytes("csv", ascii("a,b,c\n1,2,3")), "csv"));
    }
}
