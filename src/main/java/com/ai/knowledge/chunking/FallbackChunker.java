package com.ai.knowledge.chunking;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底切分策略: 封装 Spring AI 的 {@link TokenTextSplitter}(token 定长 + 标点回退)。
 *
 * <p>策略名 {@code fallback}, 是路由的最终兜底——任何 MIME 都能切, 保证路由永不 miss;
 * 参数读 {@code app.ingestion.chunking.strategies.fallback}(缺省 512/100, 与改造前行为一致,
 * 平滑迁移的基线)。总开关关闭时也由它承接(行为 = 旧固定切分)。
 */
@Component("fallback")
@RequiredArgsConstructor
public class FallbackChunker implements ChunkingStrategy {

    private final AppProperties appProperties;

    @Override
    public String name() {
        return "fallback";
    }

    @Override
    public List<Document> split(Document fullDoc) {
        IngestionProps.ChunkingStrategyProps p = props();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(p.getChunkSize())
                .withMinChunkSizeChars(p.getMinChunkChars())
                .build();
        return splitter.apply(List.of(fullDoc));
    }

    private IngestionProps.ChunkingStrategyProps props() {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        return cfg.getStrategies().getOrDefault(name(), new IngestionProps.ChunkingStrategyProps());
    }
}
