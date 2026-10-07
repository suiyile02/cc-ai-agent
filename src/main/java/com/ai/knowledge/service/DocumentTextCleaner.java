package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档文本清洗器(入库质量第一环)：在 Tika 提取之后、分块之前对全文做无损/低损清洗。
 *
 * <p>管线顺序即正确性：① 按换页符(\f)切页(PDF 页边界信号, 必须先于控制字符剔除保留);
 * ② 逐页剔除控制字符/NBSP/零宽字符; ③ U+FFFD 密度超阈值判定乱码(确定性失败);
 * ④ PDF 页眉页脚统计剔除(仅头/尾候选区 + 频率阈值 + 最小页数三重防护); ⑤ 空白规范化
 * (MD 代码块内跳过, 保表格/缩进)。清洗后为空交由既有"解析不出有效文本"兜底。
 *
 * <p>清洗只发生在(重新)入库时, 不影响存量分块; 全部规则可经
 * {@code app.ingestion.clean.*} 配置, 总开关关闭时行为与上线前完全一致。
 */
@Component
@RequiredArgsConstructor
public class DocumentTextCleaner {

    private final AppProperties appProperties;

    /**
     * 清洗文档文本(保留 Document 元数据, 仅替换 text)。
     *
     * @param docs     Tika 提取的文档(通常 1 个)
     * @param fileType 文档类型 PDF/DOCX/TXT/MD(大写, 决定规则矩阵)
     * @return 清洗后的文档列表; 总开关关闭时原样返回
     * @throws IllegalStateException U+FFFD 密度超阈值(乱码), 由入库层转为友好提示
     */
    public List<Document> clean(List<Document> docs, String fileType) {
        IngestionProps.Clean cfg = appProperties.getIngestion().getClean();
        if (!cfg.isEnabled() || docs == null || docs.isEmpty()) {
            return docs;
        }
        List<Document> out = new ArrayList<>(docs.size());
        for (Document d : docs) {
            String cleaned = cleanText(d.getText(), fileType, cfg);
            if (cleaned == null || cleaned.isBlank()) {
                // 整篇清洗后为空(纯控制字符/空白): 丢弃该文档, 交由入库层
                // "未能从文档中解析出有效文本"兜底(Document 构造器不接受空文本)
                continue;
            }
            out.add(Document.builder()
                    .id(d.getId())
                    .text(cleaned)
                    .metadata(d.getMetadata())
                    .build());
        }
        return out;
    }

    /** 单文档清洗管线。 */
    private String cleanText(String text, String fileType, IngestionProps.Clean cfg) {
        if (text == null || text.isBlank()) {
            return text;
        }
        boolean isPdf = "PDF".equalsIgnoreCase(fileType);
        // (a) 换页符切页(PDF 页边界; 其它类型整体单页)
        String[] rawPages = text.split("\f", -1);
        List<List<String>> pages = new ArrayList<>(rawPages.length);
        for (String page : rawPages) {
            pages.add(stripControls(page).lines().collect(java.util.stream.Collectors.toList()));
        }
        // (b) 乱码判定: U+FFFD 密度(在其它处理前统计, 保证口径纯净)
        checkReplacementDensity(pages, cfg);
        // (c) PDF 页眉页脚统计剔除
        if (isPdf && cfg.isPdfHeaderFooter() && pages.size() >= cfg.getHeaderFooterMinPages()) {
            stripHeaderFooter(pages, cfg);
        }
        // (d) 合并 + 空白规范化
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(normalizeWhitespace(String.join("\n", pages.get(i)), fileType, cfg));
        }
        String out = sb.toString().stripTrailing();
        return out.isEmpty() ? "" : out;
    }

    /**
     * 剔除控制字符与不可见残留：保留 \n/\t；\f 已在切页阶段消费；NBSP 等变体空格归一；
     * 零宽字符与 BOM 删除；C0/C1 控制字符与 DEL 删除。
     */
    private String stripControls(String page) {
        StringBuilder sb = new StringBuilder(page.length());
        for (int i = 0; i < page.length(); i++) {
            char c = page.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c);
            } else if (c < ' ' || c == 127 || (c >= 0x80 && c <= 0x9F)) {
                // 控制字符(含已消费的 \f)直接剔除
            } else if (c == '\u00A0' || c == '\u2007' || c == '\u202F') {
                sb.append(' ');
            } else if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                // 零宽字符/BOM: 删除
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** U+FFFD 密度检查: 超阈值说明提取结果大面积乱码(编码异常), 继续入库只会污染检索。 */
    private void checkReplacementDensity(List<List<String>> pages, IngestionProps.Clean cfg) {
        StringBuilder sb = new StringBuilder();
        pages.forEach(p -> sb.append(String.join("\n", p)));
        String all = sb.toString();
        if (all.isEmpty()) {
            return;
        }
        long fffd = all.chars().filter(c -> c == '\uFFFD').count();
        double density = (double) fffd / all.length();
        if (density > cfg.getReplacementMaxDensity()) {
            throw new IllegalStateException("文档内容疑似乱码(替换符占比 "
                    + String.format("%.1f", density * 100) + "%)，无法入库");
        }
    }

    /**
     * PDF 页眉页脚统计剔除：候选行(每页头/尾各 zoneLines 行)按规范化键统计出现页数,
     * 出现于 ≥ max(最小页数, 频率×总页数) 个页面且长度受限的行判定为页眉页脚, 从候选区删除。
     * 正文中的合法重复行(表格表头/免责声明)不在头尾候选区, 不受影响。
     */
    private void stripHeaderFooter(List<List<String>> pages, IngestionProps.Clean cfg) {
        int zone = Math.max(1, cfg.getHeaderFooterZoneLines());
        Map<String, Integer> pageFreq = new HashMap<>();
        for (List<String> page : pages) {
            Set<String> seenInPage = new HashSet<>();
            for (int i : zoneIndexes(page.size(), zone)) {
                String key = lineKey(page.get(i));
                if (key.isEmpty() || key.length() > cfg.getHeaderFooterMaxLineChars()) {
                    continue;
                }
                if (seenInPage.add(key)) {
                    pageFreq.merge(key, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(cfg.getHeaderFooterMinPages(),
                (int) Math.ceil(cfg.getHeaderFooterMinFrequency() * pages.size()));
        Set<String> junk = pageFreq.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        if (junk.isEmpty()) {
            return;
        }
        for (List<String> page : pages) {
            for (int i : zoneIndexes(page.size(), zone)) {
                if (junk.contains(lineKey(page.get(i)))) {
                    page.set(i, "");
                }
            }
        }
    }

    /** 头/尾候选区的下标集合(头部 zone 行 + 尾部 zone 行, 页短时取并集) */
    private Set<Integer> zoneIndexes(int size, int zone) {
        Set<Integer> idx = new HashSet<>();
        for (int i = 0; i < zone && i < size; i++) {
            idx.add(i);
        }
        for (int i = Math.max(0, size - zone); i < size; i++) {
            idx.add(i);
        }
        return idx;
    }

    /** 行比较键: Trim + 连续空白折叠(避免空格差异导致同页眉漏配) */
    private String lineKey(String line) {
        return line.trim().replaceAll("\\s+", " ");
    }

    /**
     * 空白规范化: 行尾空白裁剪; 2+ 连续空行收敛为 1 空行;
     * 非 MD 类型收敛行中连续空格(保行首缩进); MD 代码块内除行尾空白外原样保留。
     */
    private String normalizeWhitespace(String text, String fileType, IngestionProps.Clean cfg) {
        boolean isMd = "MD".equalsIgnoreCase(fileType);
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        boolean inCode = false;
        int blankRun = 0;
        for (String line : lines) {
            String t = line.stripTrailing();
            if (isMd) {
                String trim = t.trim();
                if (trim.startsWith("```") || trim.startsWith("~~~")) {
                    inCode = !inCode;
                }
            }
            if (inCode) {
                // 代码块内仅裁行尾, 空行/缩进原样(语法相关)
                sb.append(t).append('\n');
                continue;
            }
            boolean blank = t.isBlank();
            blankRun = blank ? blankRun + 1 : 0;
            if (blankRun > 1) {
                // 2+ 连续空行收敛为 1 空行
                continue;
            }
            if (!blank && !isMd) {
                // 中间连续空格收敛(仅 PDF/DOCX/TXT; 保留行首缩进)
                t = t.replaceAll("(?<=\\S)[ \\t]{2,}(?=\\S)", " ");
            }
            sb.append(t).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
