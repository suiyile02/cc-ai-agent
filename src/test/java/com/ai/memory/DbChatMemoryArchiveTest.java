package com.ai.memory;

import com.ai.memory.entity.ChatMemoryRaw;
import com.ai.memory.mapper.ChatMemoryRawMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DbChatMemoryArchive} seq 分配单元测试(内存 fake 表替代 mapper)。
 *
 * <p><b>回归背景</b>: appendSummary 曾直接把 MAX(seq) 当新行 seq, 与最后一行消息撞
 * {@code uk_conv_seq}(生产实测 {@code Duplicate entry '…-22'})——mock 级测试验证不了
 * seq 算术, 本测试用 fake 表复现"多轮 append → appendSummary → 再 append → 再 appendSummary"
 * 的真实时序, 钉死"新行 seq = 当前最大值 + 1"。
 */
class DbChatMemoryArchiveTest {

    private final List<ChatMemoryRaw> table = new ArrayList<>();
    private final ChatMemoryRawMapper mapper = mock(ChatMemoryRawMapper.class);
    private final DbChatMemoryArchive archive = new DbChatMemoryArchive(mapper);
    private static final String CID = "b6f3e379-test";

    @BeforeEach
    void wireFakeTable() {
        // insert → 追加进内存表(生产由 MyBatis-Plus 回填自增 id, 此处模拟)
        when(mapper.insert(any(ChatMemoryRaw.class))).thenAnswer(inv -> {
            ChatMemoryRaw row = inv.getArgument(0);
            row.setId((long) (table.size() + 1));
            table.add(row);
            return 1;
        });
        // selectObjs → 依据 wrapper 的 select 列返回 MAX(seq) 或 MAX(batch)
        when(mapper.selectObjs(any())).thenAnswer(inv -> {
            Wrapper<ChatMemoryRaw> w = inv.getArgument(0);
            boolean batchMode = String.valueOf(((QueryWrapper<ChatMemoryRaw>) w).getSqlSelect())
                    .contains("batch");
            long max = batchMode
                    ? table.stream().map(ChatMemoryRaw::getBatch).filter(Objects::nonNull)
                            .mapToLong(Integer::longValue).max().orElse(0)
                    : table.stream().mapToLong(ChatMemoryRaw::getSeq).max().orElse(0);
            return List.of(max);
        });
        // selectList → 返回快照(单会话测试, 不解析过滤条件)
        when(mapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(table));
        // delete → 清空(单会话测试)
        when(mapper.delete(any(Wrapper.class))).thenAnswer(inv -> {
            int n = table.size();
            table.clear();
            return n;
        });
    }

    private static Message user(String text) {
        return new UserMessage(text);
    }

    private static Message assistant(String text) {
        return new AssistantMessage(text);
    }

    @Test
    void appendThenSummaryAllocatesSequentialSeqsWithoutCollision() {
        // 时序还原生产故障: 多轮 append(每轮 2 行)后第一次触发摘要
        archive.append(CID, List.of(user("第一问"), assistant("第一答")));
        archive.append(CID, List.of(user("第二问"), assistant("第二答")));
        archive.appendSummary(CID, "四节摘要 v1");

        assertEquals(5, table.size(), "2 轮 × 2 条消息 + 1 条 SUMMARY");
        ChatMemoryRaw summary = table.get(4);
        assertEquals("SUMMARY", summary.getRole());
        assertEquals(5, summary.getSeq(), "SUMMARY 行 seq 必须是最大值+1(回归: 曾用 MAX 值撞唯一键)");
        assertEquals(1, summary.getBatch(), "首个压缩事件 batch=1");

        // 后续轮次与第二次摘要同样不得撞键
        archive.append(CID, List.of(user("第三问"), assistant("第三答")));
        archive.appendSummary(CID, "四节摘要 v2");

        assertEquals(8, table.size());
        assertEquals(8, table.get(7).getSeq());
        assertEquals(2, table.get(7).getBatch(), "batch 递增");
    }

    @Test
    void findByConversationIdReturnsEntriesInSeqOrder() {
        archive.append(CID, List.of(user("问"), assistant("答")));
        archive.appendSummary(CID, "摘要");

        List<ChatMemoryArchive.ArchivedEntry> entries = archive.findByConversationId(CID, 100);

        assertEquals(3, entries.size());
        assertEquals(List.of(1L, 2L, 3L), entries.stream().map(ChatMemoryArchive.ArchivedEntry::seq).toList());
        assertEquals(List.of("USER", "ASSISTANT", "SUMMARY"),
                entries.stream().map(ChatMemoryArchive.ArchivedEntry::role).toList());
    }

    @Test
    void deleteByConversationIdClearsArchive() {
        archive.append(CID, List.of(user("问"), assistant("答")));
        archive.appendSummary(CID, "摘要");

        archive.deleteByConversationId(CID);

        assertTrue(table.isEmpty(), "会话删除必须清空留档");
        assertTrue(archive.findByConversationId(CID, 100).isEmpty());
    }
}
