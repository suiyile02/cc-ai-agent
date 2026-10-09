package com.ai.knowledge.chunking;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档切分策略接口(2026-10 切分路由): 每种切分策略一个实现类, 策略名 = Spring bean 名,
 * 也是 {@code app.ingestion.chunking} 配置里引用的键。
 *
 * <p>职责划分: YAML 决定"什么格式走什么策略"({@code mime-to-strategy}), 策略类只做切分——
 * 通过 {@link ChunkingStrategyRouter} 按 MIME 查询路由, 新增策略 = 新增实现类 + 加配置,
 * 路由代码零改动(不做巨型 if-else)。策略从 {@code strategies.get(自己 name())} 读取参数。
 *
 * <p><b>父子文档(小-大检索)</b>: 结构感知策略返回 {@link #producesParents()} 为 true, 并在
 * split 时给每个小块 metadata 写入内部键 {@code _parent_seq}(该小块所属父块序号)。入库层
 * 按 {@code _parent_seq} 把同组小块拼接成父块(结构单元, 如整节/整函数)写入 knowledge_chunk 表,
 * 并把小块的正式元数据 {@code parent_ref} 设为该序号。检索命中小块后按
 * {@code doc_id + parent_ref} 取父块文本喂给模型——分离"检索粒度(小块)"与"上下文粒度(父块)"。
 * <b>无实例共享状态, 线程安全</b>(metadata 是每个 chunk 独立 map)。
 */
public interface ChunkingStrategy {

    /** split 产物中表示"父块序号"的内部 metadata 键(入库层消费后由 ChunkMetadataEnricher 统一重写) */
    String PARENT_SEQ_KEY = "_parent_seq";

    /** 策略名(Spring bean 名, 也是配置里引用的键, 须与 @Component 名字一致) */
    String name();

    /**
     * 切分: 输入清洗后的全文 Document, 输出分块 Document。
     * 分块 metadata 中 {@link #PARENT_SEQ_KEY} 由结构感知策略填入(可选), 其余元数据
     * (doc_id/file_name/chunk_index/collection)由入库层统一补。
     *
     * @param fullDoc 清洗后的全文(单 Document)
     * @return 分块列表; 空/异常由调用方按"解析不出有效文本"处理
     */
    List<Document> split(Document fullDoc);

    /**
     * 是否产出父块(小-大检索)。结构感知策略(能给出更大语义单元)返回 true,
     * 并在 split 时给小块写入 {@link #PARENT_SEQ_KEY}。
     *
     * @return true=本策略的小块可以携带父块标记(入库层据此聚合父块)
     */
    default boolean producesParents() {
        return false;
    }

    /**
     * 是否**每个**小块都必须携带父块标记。
     *
     * <p>结构感知策略(md-structure/code-line/table-row)是"结构一律有父块", 保持 true——
     * 缺标记视为约定漂移, 入库层 WARN(曾发生"聚合晚于 addMetadata 导致全量缺键"的顺序错误)。
     * code-ast 允许独立声明不带父块(父块=自身时展开无意义), 覆写为 false。
     *
     * @return true=缺标记属于异常(默认); false=允许部分小块无父块
     */
    default boolean everyChunkHasParent() {
        return true;
    }
}
