package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文递归字符切分策略: 按中文标点优先级逐级回退切块, 替代 token 硬切。
 *
 * <p>算法: 文本按分隔符层级(句末标点 → 逗号/顿号 → 词/字符)递归切分, 每块目标
 * {@code chunkSize}(token 由项目 TokenCounter 估算); 块尾尽量停在完整句, 永不从任意
 * token 中间腰斩。支持相邻块重叠({@code overlapTokens}), 缓解"无重叠跨块概念被切"的短板。
 * 对代码/表格这类标点稀疏文本会退化为接近硬切——它们由其它策略承接。
 */
@Component("recursive-cn")
@RequiredArgsConstructor
public class ChineseRecursiveChunker implements ChunkingStrategy {

    /** 分隔符层级: 从大到小, 前面的优先级更高(块尾优先停在这里) */
    private static final String[][] SEPARATORS = {
            {"。", "！", "？", "；"},
            {"，", "、", ":"},
            {" ", "\u3000"},
    };

    private final AppProperties appProperties;

    @Override
    public String name() {
        return "recursive-cn";
    }

    @Override
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        int overlap = Math.max(0, p.getOverlapTokens());
        String text = fullDoc.getText() == null ? "" : fullDoc.getText();
        List<String> pieces = new ArrayList<>();
        // 迭代切分(不用递归: 大文档块数可达数万, 递归会栈溢出)
        int targetChars = Math.max(1, p.getChunkSize());
        int start = 0;
        while (start < text.length()) {
            int remaining = text.length() - start;
            if (remaining <= targetChars) {
                String tailPiece = text.substring(start).trim();
                if (!tailPiece.isEmpty()) {
                    pieces.add(tailPiece);
                }
                break;
            }
            int cut = findCut(text, start, targetChars, SEPARATORS, 0);
            if (cut <= start) {
                // 全部分隔符都无法在窗口内断开 → 退化为固定字符宽硬切(兜底, 保进度)
                cut = Math.min(text.length(), start + targetChars);
            }
            String piece = text.substring(start, cut).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            start = cut;
        }
        List<Document> out = new ArrayList<>(pieces.size());
        int overlapChars = estimateChars(overlap, text);
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            if (i > 0 && overlapChars > 0) {
                // 与前一块末尾重叠 overlapChars 个字符(重叠内容重新入块, 缓解跨块断义)
                String prev = pieces.get(i - 1);
                String tail = prev.length() > overlapChars ? prev.substring(prev.length() - overlapChars) : prev;
                piece = tail + piece;
            }
            out.add(Document.builder().text(piece).metadata(fullDoc.getMetadata()).build());
        }
        return out;
    }

    /** 在 [start, start+targetChars] 内找最后一个可停分隔符位置(递归加深层级, 最多 3 层) */
    private int findCut(String text, int start, int targetChars, String[][] seps, int level) {
        if (level >= seps.length) {
            return -1;
        }
        int windowEnd = Math.min(text.length(), start + targetChars);
        int best = -1;
        for (String sep : seps[level]) {
            int idx = text.indexOf(sep, start);
            while (idx != -1 && idx < windowEnd) {
                best = Math.max(best, idx + sep.length());
                idx = text.indexOf(sep, idx + sep.length());
            }
        }
        if (best == -1) {
            return findCut(text, start, targetChars, seps, level + 1);
        }
        return best;
    }

    /** overlapTokens(字节/token 近似) → 重叠字符数: 中文约 1 token/字, 保守取 1.5 倍 */
    private int estimateChars(int overlapTokens, String text) {
        if (overlapTokens <= 0 || text.isEmpty()) {
            return 0;
        }
        return Math.min(text.length(), (int) (overlapTokens * 1.5));
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
