package com.ai.knowledge.service;

import com.ai.knowledge.DocumentVisibility;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ai.common.WarnThrottle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link DocumentVisibility} 实现：实时查询 {@code knowledge_document.enabled=0} 的文档集合。
 *
 * <p>降级口径：查询失败返回空集(禁用过滤失效, 禁用文档可能被检出)并 WARN 节流——
 * 检索可用性优先于过滤严格性; 本机 MySQL 即核心依赖, 该降级窗口实际上与整体故障重合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVisibilityImpl implements DocumentVisibility {

    private final KnowledgeDocumentMapper documentMapper;

    /** 降级告警节流: 每次检索都会调用, 失败时折成 60 秒一条(见 {@link WarnThrottle}) */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    @Override
    public Set<Long> disabledDocIds() {
        try {
            return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                            .select(KnowledgeDocument::getId)
                            .eq(KnowledgeDocument::getEnabled, false))
                    .stream()
                    .map(KnowledgeDocument::getId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            degraded.warn("禁用文档集合查询失败, 本次检索按全部启用处理: {}", e.getMessage());
            return Set.of();
        }
    }
}
