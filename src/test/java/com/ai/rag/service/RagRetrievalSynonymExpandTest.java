package com.ai.rag.service;

import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索同义词扩展测试：命中配置组内任一变体时把其余变体追加到检索句末尾。
 *
 * <p>动机(2026-11-01 实测)：问"员工离职需要办理哪些手续？"时，含"离职流程"的分块语义/BM25
 * 双双失配(语义分不过阈值被判无据)；扩展后两路都能命中。扩展是确定性规则，只影响检索句、
 * 不改用户原话，因此不影响语义缓存键的正确性。
 */
class RagRetrievalSynonymExpandTest {

    @Test
    void appendsMissingVariantWhenQueryMatchesOne() {
        String expanded = RagRetrievalService.expandSynonyms(
                "员工离职需要办理哪些手续？", List.of("手续|流程"));

        assertEquals("员工离职需要办理哪些手续？ 流程", expanded);
    }

    @Test
    void expandsInTheOtherDirectionToo() {
        String expanded = RagRetrievalService.expandSynonyms(
                "离职流程是什么", List.of("手续|流程"));

        assertEquals("离职流程是什么 手续", expanded);
    }

    @Test
    void keepsQueryWhenAllVariantsAlreadyPresent() {
        String query = "离职手续和离职流程分别是什么";

        assertEquals(query, RagRetrievalService.expandSynonyms(query, List.of("手续|流程")));
    }

    @Test
    void keepsQueryWhenNoGroupMatches() {
        String query = "年假有多少天";

        assertEquals(query, RagRetrievalService.expandSynonyms(query, List.of("手续|流程")));
    }

    @Test
    void expandsEveryHitGroupInOrder() {
        String expanded = RagRetrievalService.expandSynonyms(
                "离职手续和报销标准", List.of("手续|流程", "报销|费用报销"));

        assertEquals("离职手续和报销标准 流程 费用报销", expanded);
    }

    @Test
    void blankVariantsAreIgnored() {
        String expanded = RagRetrievalService.expandSynonyms(
                "离职手续", List.of("手续||流程"));

        assertEquals("离职手续 流程", expanded);
    }

    @Test
    void degenerateInputsAreReturnedAsIs() {
        assertNull(RagRetrievalService.expandSynonyms(null, List.of("手续|流程")));
        assertEquals("", RagRetrievalService.expandSynonyms("", List.of("手续|流程")));
        assertEquals("   ", RagRetrievalService.expandSynonyms("   ", List.of("手续|流程")));
        assertEquals("离职手续", RagRetrievalService.expandSynonyms("离职手续", List.of()));
        assertEquals("离职手续", RagRetrievalService.expandSynonyms("离职手续", null));
        // 空组与全空白组等价于未配置
        assertEquals("离职手续", RagRetrievalService.expandSynonyms("离职手续", List.of("|", " ")));
    }

    @Test
    void doRetrieveFeedsTheExpandedQueryToRecall() {
        AppProperties props = new AppProperties();
        props.getRag().setSynonymGroups(List.of("手续|流程"));
        HybridRecaller recaller = mock(HybridRecaller.class);
        when(recaller.recall(anyString(), anyInt())).thenReturn(new HybridRecaller.Recall(
                List.<Document>of(), List.<Document>of(), 0.0, false, false));
        RagRetrievalService service = new RagRetrievalService(
                props, recaller, null, null, null, null);

        service.retrieve("员工离职需要办理哪些手续？", 5, 0.43);

        verify(recaller).recall(eq("员工离职需要办理哪些手续？ 流程"), eq(5));
    }
}
