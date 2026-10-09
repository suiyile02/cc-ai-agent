package com.ai.knowledge.ingestion;

import com.ai.knowledge.chunking.ChunkedDocument;
import com.ai.knowledge.chunking.ChunkingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父块聚合阶段(小-大检索): 按小块的内部键 {@code _parent_seq} 把同组小块文本拼接成父块。
 *
 * <p><b>必须在 {@link ChunkMetadataEnricher} 之前调用</b>——打标会把内部键转成正式
 * {@code parent_ref} 并剔除, 之后本类会读不到键而退化(曾发生"聚合晚于 addMetadata"的顺序错误)。
 * 无标记的小块不参与聚合(如 code-ast 的独立声明); 但策略声明"每块必有父块"时,
 * 缺标记属于约定漂移, 记 WARN 让问题可见。
 */
@Slf4j
@Component
public class ParentBlockAggregator {

    /**
     * 按小块的 {@code _parent_seq} 聚合父块文本: 同父块序号的小块文本拼接成一个父块。
     *
     * @param chunked 切分阶段产物(小块 + 策略父块约定)
     * @return 父块序号 → 父块文本
     */
    public Map<Integer, String> aggregate(ChunkedDocument chunked) {
        List<Document> chunks = chunked.chunks();
        Map<Integer, StringBuilder> acc = new LinkedHashMap<>();
        int keyless = 0;
        for (Document chunk : chunks) {
            Object seqObj = chunk.getMetadata().get(ChunkingStrategy.PARENT_SEQ_KEY);
            if (!(seqObj instanceof Number n)) {
                keyless++;
                continue;
            }
            acc.computeIfAbsent(n.intValue(), k -> new StringBuilder())
                    .append(chunk.getText()).append('\n');
        }
        if (keyless > 0 && chunked.everyChunkHasParent()) {
            log.warn("策略 {} 声明每块必有父块, 但 {} 个块缺内部键 {}——按无父块处理(顺序错误或约定漂移?)",
                    chunked.strategyName(), keyless, ChunkingStrategy.PARENT_SEQ_KEY);
        }
        Map<Integer, String> parents = new LinkedHashMap<>();
        acc.forEach((seq, sb) -> {
            String text = sb.toString().stripTrailing();
            if (!text.isEmpty()) {
                parents.put(seq, text);
            }
        });
        return parents;
    }
}
