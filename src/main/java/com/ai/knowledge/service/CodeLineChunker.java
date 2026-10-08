package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码类切分策略: 按空行/缩进块切分, 尽量保住函数/类边界。
 *
 * <p>思路: 零缩进的行(顶层声明/函数定义/类定义)是潜在的块起点——一个"缩进块"从零缩进行开始,
 * 到下一个零缩进行(或其后的空行组)之前结束; 超长块按零缩进行继续细分, 缩进块内部不再腰斩
 * 到语句中间。空行被吞掉(不产生空块)。不丢内容: 拼接各块可还原全部非空行。
 */
@Component("code-line")
@RequiredArgsConstructor
public class CodeLineChunker implements ChunkingStrategy {

    private final AppProperties appProperties;

    @Override
    public String name() {
        return "code-line";
    }

    @Override
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        int minIndent = Math.max(0, p.getMinIndent());
        String[] lines = text.split("\n", -1);
        List<String> blocks = splitByIndent(lines, minIndent);
        List<Document> out = new ArrayList<>();
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            // 超长缩进块按标点/字符回退再切(代码标点稀疏, 多半走硬切兜底; 不丢内容)
            List<String> pieces = RecursivePunctuationSplitter.split(block, p.getChunkSize());
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
     * 按缩进分组: 顶层行(缩进 < minIndent)开启新块, 缩进行并入当前块。
     *
     * <p>规则: ①相邻顶层行并入同一块(纯脚本不切碎); ②缩进体结束回到顶层时收尾开新块
     * (函数/类边界); ③空行分隔的两个顶层块之间也收尾(空行是常见函数分隔)。这样既保住
     * 函数/类的缩进体, 又把"连续顶层语句 + 空行 + 连续顶层语句"切成两块。
     */
    private List<String> splitByIndent(String[] lines, int minIndent) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        boolean lastIndented = false;   // 上一条非空行是否处于缩进体
        boolean sawBlank = false;       // 上一条是否为空行(空行分隔的两个块要分开)
        for (String line : lines) {
            if (line.isBlank()) {
                sawBlank = true;
                continue;
            }
            int indent = leadingSpaces(line);
            boolean indented = indent >= minIndent;
            if (current == null) {
                current = new StringBuilder();
            } else if (sawBlank || (!indented && lastIndented)) {
                // 空行分隔 或 从缩进体回到顶层: 收尾当前块, 开启新块
                String b = current.toString().stripTrailing();
                if (!b.isBlank()) {
                    blocks.add(b);
                }
                current = new StringBuilder();
            }
            current.append(line).append('\n');
            lastIndented = indented;
            sawBlank = false;
        }
        if (current != null) {
            String b = current.toString().stripTrailing();
            if (!b.isBlank()) {
                blocks.add(b);
            }
        }
        return blocks;
    }

    /** 行首空格数(仅空格; 制表符按 4 空格折算) */
    private int leadingSpaces(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                n++;
            } else if (c == '\t') {
                n += 4;
            } else {
                break;
            }
        }
        return n;
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
