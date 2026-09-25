package com.ai.knowledge.service;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地文件存储(需求 1.1：本地/OSS/MinIO, MVP 落地本地磁盘)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final AppProperties appProperties;

    /**
     * 保存上传文件到本地磁盘(按 用户/日期 分目录, 文件名取 UUID 防冲突)。
     *
     * @param file   上传文件
     * @param userId 上传人 ID(用于目录划分)
     * @return 文件绝对存储路径
     * @throws BusinessException 文件名为空或保存失败
     */
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

    /**
     * 删除本地文件(尽力而为, 失败仅告警不影响业务主流程)。
     *
     * @param storagePath 文件绝对路径
     */
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

    /**
     * 校验文件内容与扩展名一致(魔数检查, P2-1)：
     * PDF 必须以 %PDF- 开头; DOCX 必须为 ZIP 容器(PK);
     * TXT/MD 拒绝伪装成文本的二进制(以 PK/%PDF 开头或头部含 NUL 字节)。
     *
     * @param file 上传文件
     * @param ext  扩展名(小写)
     * @throws BusinessException 内容与扩展名不符
     */
    public void validateContent(MultipartFile file, String ext) {
        byte[] head;
        try (var in = file.getInputStream()) {
            head = in.readNBytes(8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "无法读取文件内容进行校验");
        }
        boolean isPdf = head.length >= 5 && head[0] == 0x25 && head[1] == 0x50 && head[2] == 0x44
                && head[3] == 0x46 && head[4] == 0x2D;
        boolean isZip = head.length >= 4 && head[0] == 0x50 && head[1] == 0x4B
                && head[2] == 0x03 && head[3] == 0x04;
        boolean binaryHead = head.length >= 4 && (isZip || isPdf);
        boolean hasNul = false;
        for (byte b : head) {
            if (b == 0x00) {
                hasNul = true;
                break;
            }
        }
        switch (ext) {
            case "pdf" -> {
                if (!isPdf) {
                    throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "文件内容不是有效的 PDF");
                }
            }
            case "docx" -> {
                if (!isZip) {
                    throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "文件内容不是有效的 DOCX(应为 ZIP 容器)");
                }
                checkZipBomb(file);
            }
            default -> {
                // txt/md: 拒绝伪装成文本的二进制
                if (binaryHead || hasNul) {
                    throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED,
                            "文件内容为二进制数据, 与 ." + ext + " 不符");
                }
            }
        }
    }

    /**
     * 解压炸弹预检(A2)：流式遍历 docx(zip) 全部条目并累计解压后字节数,
     * 超过 max-uncompressed-bytes 或条目数超限即拒绝——在进入 Tika 解析前拦截,
     * 避免恶意压缩包长时间占用入库线程。
     *
     * @param file 上传的 docx 文件
     * @throws BusinessException 解压后内容超限或条目过多
     */
    private void checkZipBomb(MultipartFile file) {
        long maxBytes = appProperties.getIngestion().getMaxUncompressedBytes();
        int maxEntries = appProperties.getIngestion().getMaxZipEntries();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zin = new ZipInputStream(file.getInputStream())) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED,
                            "文件内部条目过多(疑似压缩炸弹)");
                }
                int n;
                while ((n = zin.read(buffer)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED,
                                "文件解压后内容过大(疑似压缩炸弹)");
                    }
                }
                zin.closeEntry();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "无法读取文件内容进行校验");
        }
        log.debug("docx 解压预检通过: 解压后 {} 字节, 条目 {} 个", total, entries);
    }

    /**
     * 取文件扩展名(小写, 不含点)。
     *
     * @param filename 文件名
     * @return 扩展名, 无扩展名返回空串
     */
    public static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot == -1) ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
