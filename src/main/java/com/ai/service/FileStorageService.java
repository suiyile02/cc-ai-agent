package com.ai.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地文件存储(需求: 本地/OSS/MinIO, MVP 落地为本地磁盘)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final AppProperties appProperties;

    /** 保存上传文件, 返回绝对存储路径 */
    public String save(MultipartFile file, Long userId) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_NAME_EMPTY);
        }
        String ext = extensionOf(original);
        Path dir = Paths.get(appProperties.getStorage().getPath(),
                String.valueOf(userId), LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + ext);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("文件已保存: {} -> {}", original, target.toAbsolutePath());
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("文件保存失败: {}", original, e);
            throw new BusinessException(ErrorCode.FILE_SAVE_FAILED);
        }
    }

    /** 删除文件(尽力而为) */
    public void delete(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException e) {
            log.warn("删除文件失败(忽略): {}", storagePath);
        }
    }

    public static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot == -1) ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
