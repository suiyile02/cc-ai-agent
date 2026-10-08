package com.ai.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;

/**
 * MIME 类型解析: 用 Tika 直接探测文件(内容+文件名), 探测失败/不可判定时按扩展名推导标准 MIME。
 *
 * <p>为什么要自己探测: Spring AI 的 {@code TikaDocumentReader} 只把 {@code source} 放进
 * Document metadata, **不保留 Tika 探测的 content-type**——路由键拿不到。因此在入库层对
 * 已落盘文件做一次 Tika 探测(只读文件头, 开销极小)。
 * 切分路由的键是 MIME 而非扩展名: 按内容探测能识别伪装扩展名(如 .txt 实为 PDF), 更可靠。
 */
@Slf4j
@Component
public class MimeResolver {

    /** Tika 探测器(线程安全; 内容+扩展名联合判定) */
    private final Tika tika = new Tika();

    /** 扩展名(小写) → 标准 MIME(Tika 不可判定时兜底) */
    private static final Map<String, String> EXT_TO_MIME = Map.ofEntries(
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("java", "text/x-java-source"),
            Map.entry("py", "text/x-python"),
            Map.entry("js", "text/javascript"),
            Map.entry("ts", "text/typescript"),
            Map.entry("go", "text/x-go"),
            Map.entry("sql", "text/x-sql"),
            Map.entry("yaml", "text/yaml"),
            Map.entry("yml", "text/yaml"),
            Map.entry("sh", "text/x-shellscript"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    /**
     * 解析文档 MIME(归一化前, 可能带参数, 由路由器归一化)。
     *
     * @param storagePath 已落盘文件的绝对路径
     * @param fileType    文档扩展名大写(入库记录字段, Tika 不可判定时兜底)
     * @return MIME 字符串(探测失败回退扩展名映射, 仍未知则 text/plain)
     */
    public String resolve(String storagePath, String fileType) {
        if (storagePath != null && !storagePath.isBlank()) {
            try {
                String detected = tika.detect(new File(storagePath));
                // octet-stream = Tika 认不出具体类型, 交给扩展名映射(可能更精确)
                if (detected != null && !detected.isBlank()
                        && !"application/octet-stream".equals(detected)) {
                    return detected;
                }
            } catch (Exception e) {
                log.warn("Tika MIME 探测失败(回退扩展名推导): {}", e.getMessage());
            }
        }
        String ext = fileType == null ? "" : fileType.toLowerCase();
        return EXT_TO_MIME.getOrDefault(ext, "text/plain");
    }
}
