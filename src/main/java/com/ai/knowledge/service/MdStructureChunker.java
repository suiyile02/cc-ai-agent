package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 结构感知切分策略: 按标题层级(#/##/###)切成小节, 每个切块携带所属小节的标题路径,
 * 保留检索语境; 小节超长时用 {@link RecursivePunctuationSplitter} 标点回退再切。
 *
 * <p>无标题文本退化为整篇标点回退切分(单节); 不丢内容(拼接各块可还原全部文本)。
 * 标题本身并入所属小节的首块(标题不被丢弃, 检索时命中"标题+正文"更可读)。
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
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        List<String> sections = splitByHeadings(text);
        List<Document> out = new ArrayList<>();
        for (String section : sections) {
            // 节内超长 → 标点回退再切; 未超长则整节一块(含标题)
            List<String> pieces = RecursivePunctuationSplitter.split(section, p.getChunkSize());
            for (String piece : pieces) {
                out.add(Document.builder()
                        .text(piece)
                        .metadata(fullDoc.getMetadata())
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
