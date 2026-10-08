package com.ai.knowledge.service;

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
 * Markdown 结构感知切分策略: 按标题层级(#/##/###)切成小节, 每个小块携带所属小节序号
 * ({@link ChunkingStrategy#PARENT_SEQ_KEY}), 入库层据此把同节小块拼成父块(小-大检索);
 * 小节超长时用 {@link RecursivePunctuationSplitter} 标点回退再切。
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
     * 按 Markdown 标题(#~####)切分小节: 每节 = "上一个标题行到下一个标题行之前"。
     * 标题行本身保留在本节开头; 无标题文本返回单节; 前缀无标题的引言并入第一节。
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
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (isHeading(line)) {
                // 遇到新标题: 把已收集的节收尾(节非空才入), 开启新节
                if (current.length() > 0 && current.toString().trim().length() > 0) {
                    sections.add(current.toString().trim());
                }
                current = new StringBuilder();
                current.append(line).append('\n');
            } else {
                current.append(line).append('\n');
            }
        }
        if (current.length() > 0 && current.toString().trim().length() > 0) {
            sections.add(current.toString().trim());
        }
        return sections;
    }

    /** 判定是否为 Markdown 标题行: 行首 1~4 个 # 后跟空格(排除 ## 紧跟文本的伪标题) */
    private boolean isHeading(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        int hash = 0;
        while (hash < line.length() && hash < 4 && line.charAt(hash) == '#') {
            hash++;
        }
        return hash >= 1 && hash <= 4
                && hash < line.length()
                && line.charAt(hash) == ' ';
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
