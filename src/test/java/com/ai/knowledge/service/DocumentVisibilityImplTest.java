package com.ai.knowledge.service;

import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentVisibilityImpl} 单元测试: 禁用集合实时查询与降级口径——
 * 查询失败返回空集(过滤失效, WARN 节流), 绝不让检索因过滤层故障而中断。
 */
class DocumentVisibilityImplTest {

    private final KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
    private final DocumentVisibilityImpl visibility = new DocumentVisibilityImpl(mapper);

    @Test
    void returnsIdsOfDisabledDocuments() {
        KnowledgeDocument a = new KnowledgeDocument();
        a.setId(7L);
        a.setEnabled(false);
        KnowledgeDocument b = new KnowledgeDocument();
        b.setId(9L);
        b.setEnabled(false);
        when(mapper.selectList(any())).thenReturn(List.of(a, b));

        Set<Long> disabled = visibility.disabledDocIds();

        assertEquals(Set.of(7L, 9L), disabled);
        // 查询条件必须锁定 enabled=0 且只取 id 列
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocument>> qw =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(qw.capture());
        assertTrue(qw.getValue().getSqlSelect().contains("id"), "只查 id 列");
    }

    @Test
    void queryFailureDegradesToEmptySet() {
        when(mapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertEquals(java.util.Set.of(), visibility.disabledDocIds(),
                "过滤层故障不得中断检索, 降级为空集(全部按启用处理)");
    }
}
