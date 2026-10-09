package com.ai.knowledge.storage;

import com.ai.knowledge.ParentBlockStore;
import com.ai.knowledge.entity.KnowledgeChunk;
import com.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ai.common.WarnThrottle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ParentBlockStore} 实现(knowledge_chunk 表)。
 *
 * <p>降级口径: 父块写入失败仅 WARN(小块向量照常入库, 只是该文档暂缺父块展开能力);
 * 读取失败返回空(检索照常按小块文本注入, 不因父块查询失败而降级为无资料)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentBlockStoreImpl implements ParentBlockStore {

    private final KnowledgeChunkMapper chunkMapper;

    /** 降级告警节流 */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    @Override
    public void replaceParents(Long docId, Map<Integer, String> parents) {
        if (parents == null || parents.isEmpty()) {
            deleteByDocId(docId);
            return;
        }
        try {
            deleteByDocId(docId);
            LocalDateTime now = LocalDateTime.now();
            for (Map.Entry<Integer, String> e : parents.entrySet()) {
                KnowledgeChunk c = new KnowledgeChunk();
                c.setDocId(docId);
                c.setParentSeq(e.getKey());
                c.setContent(e.getValue());
                c.setCreatedAt(now);
                chunkMapper.insert(c);
            }
        } catch (Exception e) {
            degraded.warn("父块写入失败(忽略, 该文档暂缺父块展开): docId={}, err={}", docId, e.getMessage());
        }
    }

    @Override
    public Map<Integer, String> findParents(Long docId, List<Integer> parentSeqs) {
        if (docId == null || parentSeqs == null || parentSeqs.isEmpty()) {
            return Map.of();
        }
        try {
            return chunkMapper.selectList(new QueryWrapper<KnowledgeChunk>()
                            .eq("doc_id", docId)
                            .in("parent_seq", parentSeqs))
                    .stream()
                    .collect(Collectors.toMap(KnowledgeChunk::getParentSeq, KnowledgeChunk::getContent));
        } catch (Exception e) {
            degraded.warn("父块读取失败(忽略, 按小块文本注入): docId={}, err={}", docId, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void deleteByDocId(Long docId) {
        try {
            chunkMapper.delete(new QueryWrapper<KnowledgeChunk>().eq("doc_id", docId));
        } catch (Exception e) {
            degraded.warn("父块清理失败(忽略): docId={}, err={}", docId, e.getMessage());
        }
    }
}
