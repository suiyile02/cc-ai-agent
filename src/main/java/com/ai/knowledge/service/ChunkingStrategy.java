package com.ai.knowledge.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档切分策略接口(2026-10 切分路由): 每种切分策略一个实现类, 策略名 = Spring bean 名,
 * 也是 {@code app.ingestion.chunking} 配置里引用的键。
 *
 * <p>职责划分: YAML 决定"什么格式走什么策略"({@code mime-to-strategy}), 策略类只做切分——
 * 通过 {@link ChunkingStrategyRouter} 按 MIME 查询路由, 新增策略 = 新增实现类 + 加配置,
 * 路由代码零改动(不做巨型 if-else)。策略从 {@code strategies.get(自己 name())} 读取参数。
 */
public interface ChunkingStrategy {

    /** 策略名(Spring bean 名, 也是配置里引用的键, 须与 @Component 名字一致) */
    String name();

    /**
     * 切分: 输入清洗后的全文 Document, 输出分块 Document。
     * 分块的 metadata 由入库层统一补(doc_id/file_name/chunk_index/collection), 本方法不需要填。
     *
     * @param fullDoc 清洗后的全文(单 Document)
     * @return 分块列表; 空/异常由调用方按"解析不出有效文本"处理
     */
    List<Document> split(Document fullDoc);
}
