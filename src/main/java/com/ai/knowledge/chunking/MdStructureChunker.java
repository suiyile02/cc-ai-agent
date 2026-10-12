package com.ai.knowledge.chunking;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown 结构感知切分策略: 只在**文档内最浅标题层级**(如 #/##)断节, 次级标题连同正文并入所在大节
 * ——杜绝"零正文光杆标题块"(旧规则让每个标题独立成节, 短标题块既占召回名额又喂不出内容,
 * BM25 长度归一还使其分数虚高)。每个小块携带所属节序号
 * ({@link ChunkingStrategy#PARENT_SEQ_KEY}), 入库层据此把同节小块拼成父块(小-大检索);
 * 大节超长时用 {@link RecursivePunctuationSplitter} 标点回退再切。
 *
 * <p>无标题文本退化为整篇单节(全部小块同父块); 不丢内容(拼接各块可还原全部文本)。
 * 标题并入所属小节的首块。无共享实例状态, 线程安全。
 */
@Component("md-structure")
@RequiredArgsConstructor
public class MdStructureChunker implements ChunkingStrategy {

    private final AppProperties appProperties;

    @Override
    public String name() {
        return "md-structure";
    }

    @Override
    public boolean producesParents() {
        return true; // Markdown 节是天然父块(小-大检索)
    }

    @Override
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        List<String> sections = splitByHeadings(text);
        List<Document> out = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            // 节内超长 → 标点回退再切; 未超长则整节一块(含标题)
            List<String> pieces = RecursivePunctuationSplitter.split(sections.get(s), p.getChunkSize());
            for (String piece : pieces) {
                Map<String, Object> md = new HashMap<>(fullDoc.getMetadata());
                md.put(PARENT_SEQ_KEY, s); // 小节序号 = 父块序号(入库层聚合)
                out.add(Document.builder()
                        .text(piece)
                        .metadata(md)
                        .build());
            }
        }
        return out;
    }

    /**
     * 按**最浅标题层级**切节: 先扫描全文取最浅的 1~4 级标题深度, 再只在同深度标题处断节。
     * 次级标题行保留在节内(语境不丢)。唯一例外: 首标题之前存在无标题引言时强制以 # 为界
     * (封面标题作顶级、引言并入其节)——否则整篇文档会挤成一个超大单节父块。
     *
     * @param text 原文
     * @return 小节列表(按出现顺序, 内容不丢)
     */
    private List<String> splitByHeadings(String text) {
        List<String> sections = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sections;
        }
        String[] lines = text.split("\n", -1);
        int topLevel = shallowestHeadingDepth(lines);
        if (topLevel < 0) {
            sections.add(text.trim());
            return sections;
        }
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (headingDepth(line) == topLevel) {
                // 遇到新顶级标题: 把已收集的节收尾(节非空才入), 开启新节
                if (current.length() > 0 && current.toString().trim().length() > 0) {
                    sections.add(current.toString().trim());
                }
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0 && current.toString().trim().length() > 0) {
            sections.add(current.toString().trim());
        }
        return sections;
    }

    /** 全文最浅标题深度(1~4); 存在前缀无标题引言时强制以 # 为界; 无任何标题返回 -1。 */
    private static int shallowestHeadingDepth(String[] lines) {
        int min = -1;
        boolean preamble = false;
        for (int i = 0; i < lines.length; i++) {
            int depth = headingDepth(lines[i]);
            if (depth < 0) {
                continue;
            }
            if (min < 0) {
                // 第一个标题之前已有实质文本 → 引言段场景(封面标题作顶级, 引言并入其节)
                preamble = i > indexOfFirstNonBlank(lines);
                min = depth;
            } else {
                min = Math.min(min, depth);
            }
        }
        if (min < 0) {
            return -1;
        }
        return preamble ? 1 : min;
    }

    /** 判定标题行并返回层级(1~4); 非标题返回 -1。规则: 行首 1~4 个 # 后跟空格。 */
    private static int headingDepth(String line) {
        if (line == null || line.isBlank()) {
            return -1;
        }
        int hash = 0;
        while (hash < line.length() && hash < 4 && line.charAt(hash) == '#') {
            hash++;
        }
        return hash >= 1 && hash <= 4 && hash < line.length() && line.charAt(hash) == ' ' ? hash : -1;
    }

    /** 首个非空白行的下标(全空返回 0)。 */
    private static int indexOfFirstNonBlank(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return 0;
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
