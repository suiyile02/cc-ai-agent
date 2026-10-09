package com.ai.knowledge.chunking;

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
 * <p>切分算法见 {@link RecursivePunctuationSplitter}(句末标点 → 逗号/顿号 → 空格 → 硬切;
 * 迭代实现, 块尾尽量停在完整句)。本类只负责读取参数与施加相邻块重叠({@code overlapTokens}),
 * 缓解"无重叠跨块概念被切"的短板。对代码/表格这类标点稀疏文本会退化为接近硬切——它们由其它策略承接。
 */
@Component("recursive-cn")
@RequiredArgsConstructor
public class ChineseRecursiveChunker implements ChunkingStrategy {

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
        List<String> pieces = RecursivePunctuationSplitter.split(text, p.getChunkSize());
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

    /** overlapTokens(字节/token 近似) → 重叠字符数: 中文约 1 token/字, 保守取 1.5 倍 */
    private int estimateChars(int overlapTokens, String text) {
        if (overlapTokens <= 0 || text.isEmpty()) {
            return 0;
        }
        return Math.min(text.length(), (int) (overlapTokens * 1.5));
    }

    /** 取当前策略的分块配置, 缺失时回退默认空配置 */
    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
