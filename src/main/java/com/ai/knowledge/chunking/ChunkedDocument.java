package com.ai.knowledge.chunking;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 切分阶段产物: 小块列表 + 所用策略名 + 该策略的父块约定。
 *
 * <p>由 {@link DocumentChunker} 产出, 交给入库编排层做父块聚合与元数据打标。
 * {@code producesParents}/{@code everyChunkHasParent} 承载策略声明的约定(见
 * {@link ChunkingStrategy#producesParents()} / {@link ChunkingStrategy#everyChunkHasParent()}),
 * 让入库层"是否需要聚合""缺键是否算漂移"的判断无需再回调策略。
 *
 * @param chunks            切分产出的小块(结构策略的小块携带内部键 {@code _parent_seq})
 * @param strategyName      所用策略名(告警/审计用)
 * @param producesParents   策略是否产出父块
 * @param everyChunkHasParent 策略是否声明每块必有父块标记
 */
public record ChunkedDocument(List<Document> chunks, String strategyName,
                              boolean producesParents, boolean everyChunkHasParent) {
}
