package com.ai.knowledge.service;

import com.ai.common.BusinessException;
import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileStorageService} 流式 SHA-256 单元测试(P3-9 判重键的真实计算逻辑):
 * 已知向量校验(MultipartFile 与本地文件两入口)、内容差异导致哈希不同。
 */
class FileStorageServiceTest {

    private final FileStorageService service =
            new FileStorageService(new AppProperties());

    /** "abc" 的标准 SHA-256(公开测试向量) */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void multipartHashMatchesKnownVector() {
        MultipartFile file = new MockMultipartFile("file", "测试.md", "text/markdown",
                "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(SHA256_ABC, service.sha256Hex(file));
    }

    @Test
    void localFileHashMatchesKnownVector(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("sample.md");
        Files.writeString(path, "abc", StandardCharsets.UTF_8);

        assertEquals(SHA256_ABC, service.sha256Hex(path.toString()));
    }

    @Test
    void differentContentYieldsDifferentHash() {
        String h1 = service.sha256Hex(new MockMultipartFile("f", "a.md", "text/markdown",
                "内容一".getBytes(StandardCharsets.UTF_8)));
        String h2 = service.sha256Hex(new MockMultipartFile("f", "b.md", "text/markdown",
                "内容二".getBytes(StandardCharsets.UTF_8)));

        assertEquals(64, h1.length());
        assertFalse(h1.equals(h2), "不同内容哈希必须不同");
    }

    @Test
    void missingLocalFileFailsWithFriendlyBusinessError() {
        assertThrows(BusinessException.class,
                () -> service.sha256Hex("Z:/不存在的路径/不存在.md"));
    }
}
