package com.ai.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 知识库父块存储契约(小-大检索): 入库层写入父块(结构单元, 供"命中小块 → 取父块喂模型"),
 * 检索层(rag)按 {@code doc_id + parent_ref} 读取。
 *
 * <p>数据归 knowledge 模块所有, 契约定义在生产方根包(AGENTS.md 规则 5); 父块随文档删除/重处理清理。
 * 父块不向量化(避免重复 embedding 与 Qdrant 存储), 只存文本——召回仍靠小块向量。
 */
public interface ParentBlockStore {

    /**
     * 写入一个文档的全部父块(覆盖式: 先删该文档旧父块再插)。
     *
     * @param docId    文档 ID
     * @param fileName 所属文档文件名(冗余写入父块表, 避免查父块时 join knowledge_document)
     * @param parents  父块序号 → 父块文本
     */
    void replaceParents(Long docId, String fileName, Map<Integer, String> parents);

    /**
     * 批量读取父块文本(检索时按命中小块的 doc_id+parent_ref 取)。
     *
     * @param docId  文档 ID
     * @param parentSeqs 需要的父块序号集合
     * @return 父块序号 → 父块文本(仅返回存在的)
     */
    Map<Integer, String> findParents(Long docId, List<Integer> parentSeqs);

    /**
     * 删除某文档的全部父块(文档删除/重处理时调用)。
     *
     * @param docId 文档 ID
     */
    void deleteByDocId(Long docId);
}
