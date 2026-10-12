package com.ai.knowledge.service;

import com.ai.knowledge.entity.KnowledgeChunk;
import com.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.ai.knowledge.storage.ParentBlockStoreImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ParentBlockStoreImpl} 单元测试(内存 fake 表替代 mapper)。
 *
 * <p>fake 表按 wrapper 的 {@code eq/IN} 条件真实过滤(而非忽略条件返回全表), 并模拟
 * {@code uk_doc_parent} 唯一键——这样"replaceParents 必须删旧再插新"、"findParents 必须
 * 按 doc_id+parent_seq 双条件收敛"这两条 SQL 语义才真正被执行到, 而不只是被 mock 记一笔调用。
 */
class ParentBlockStoreImplTest {

    private final List<KnowledgeChunk> table = new ArrayList<>();
    private final KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
    private final ParentBlockStoreImpl store = new ParentBlockStoreImpl(mapper);

    private static final Pattern PH = Pattern.compile("MPGENVAL\\d+");
    private static final Pattern IN_PATTERN = Pattern.compile("(\\w+)\\s+IN\\s+\\((.*)\\)");
    private static final Pattern EQ_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(.*)");

    @BeforeEach
    void wireFakeTable() {
        // insert → 追加; 同 (doc_id, parent_seq) 撞唯一键 uk_doc_parent → 与生产同样抛 DuplicateKeyException
        when(mapper.insert(any(KnowledgeChunk.class))).thenAnswer(inv -> {
            KnowledgeChunk row = inv.getArgument(0);
            boolean dup = table.stream().anyMatch(r -> Objects.equals(r.getDocId(), row.getDocId())
                    && Objects.equals(r.getParentSeq(), row.getParentSeq()));
            if (dup) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_doc_parent'");
            }
            row.setId((long) (table.size() + 1));
            table.add(row);
            return 1;
        });
        // selectList → 按 wrapper 条件过滤
        when(mapper.selectList(any())).thenAnswer(inv -> select(inv.getArgument(0)));
        // delete → 按 wrapper 条件删除, 返回删除行数
        when(mapper.delete(any(Wrapper.class))).thenAnswer(inv -> {
            List<KnowledgeChunk> doomed = select(inv.getArgument(0));
            table.removeAll(doomed);
            return doomed.size();
        });
    }

    /** 依据 wrapper 的 eq/IN 条件过滤内存表(解析生产代码实际生成的简单条件形状)。 */
    @SuppressWarnings("unchecked")
    private List<KnowledgeChunk> select(Wrapper<KnowledgeChunk> w) {
        QueryWrapper<KnowledgeChunk> qw = (QueryWrapper<KnowledgeChunk>) w;
        String body = qw.getSqlSegment().trim();
        if (body.startsWith("(") && body.endsWith(")")) {
            body = body.substring(1, body.length() - 1);
        }
        Map<String, Object> params = qw.getParamNameValuePairs();
        List<KnowledgeChunk> out = new ArrayList<>();
        for (KnowledgeChunk row : table) {
            boolean match = true;
            for (String cond : body.split(" AND ")) {
                String c = cond.trim();
                Matcher in = IN_PATTERN.matcher(c);
                Matcher eq = EQ_PATTERN.matcher(c);
                if (in.matches()) {
                    List<Object> values = new ArrayList<>();
                    for (String ph : placeholders(in.group(2))) {
                        values.add(params.get(ph));
                    }
                    match = values.contains(columnValue(row, in.group(1)));
                } else if (eq.matches()) {
                    match = Objects.equals(columnValue(row, eq.group(1)),
                            params.get(placeholders(eq.group(2)).get(0)));
                } else {
                    throw new IllegalStateException("fake 表不认识的条件: " + c);
                }
                if (!match) {
                    break;
                }
            }
            if (match) {
                out.add(row);
            }
        }
        return out;
    }

    private static List<String> placeholders(String s) {
        List<String> names = new ArrayList<>();
        Matcher m = PH.matcher(s);
        while (m.find()) {
            names.add(m.group());
        }
        return names;
    }

    private static Object columnValue(KnowledgeChunk row, String column) {
        return switch (column) {
            case "doc_id" -> row.getDocId();
            case "parent_seq" -> row.getParentSeq();
            default -> throw new IllegalStateException("fake 表不认识的列: " + column);
        };
    }

    private static Map<Integer, String> parents(Object... seqTextPairs) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < seqTextPairs.length; i += 2) {
            m.put((Integer) seqTextPairs[i], (String) seqTextPairs[i + 1]);
        }
        return m;
    }

    @Test
    void replaceParentsReplacesInsteadOfAccumulating() {
        store.replaceParents(1L, "doc.md", parents(0, "甲块 v1", 1, "乙块 v1"));
        // 重新入库(父块集合变小): 必须删旧再插新, 不能残留旧行也不能撞唯一键
        store.replaceParents(1L, "doc.md", parents(0, "甲块 v2"));

        assertEquals(1, table.size(), "旧父块必须被清掉(残留会让检索注入过期内容)");
        assertEquals(Map.of(0, "甲块 v2"), store.findParents(1L, List.of(0, 1)),
                "seq=1 已不存在; seq=0 必须是新文本");
    }

    @Test
    void findParentsScopesByDocAndSeq() {
        store.replaceParents(1L, "doc.md", parents(0, "A0", 1, "A1", 2, "A2"));
        store.replaceParents(2L, "doc.md", parents(0, "B0"));

        // 文档隔离: 不得取到 doc 2 的块; seq 过滤: 只要请求的 0 与 2, 不含 1
        assertEquals(Map.of(0, "A0", 2, "A2"), store.findParents(1L, List.of(0, 2)));
        assertEquals(Map.of(0, "B0"), store.findParents(2L, List.of(0)));
    }

    @Test
    void deleteByDocIdOnlyClearsItsOwnDoc() {
        store.replaceParents(1L, "doc.md", parents(0, "A0"));
        store.replaceParents(2L, "doc.md", parents(0, "B0"));

        store.deleteByDocId(1L);

        assertEquals(Map.of(), store.findParents(1L, List.of(0)));
        assertEquals(Map.of(0, "B0"), store.findParents(2L, List.of(0)), "不得误删其它文档的父块");
    }

    @Test
    void emptyParentsClearsHistoricalRows() {
        store.replaceParents(1L, "doc.md", parents(0, "旧父块"));
        store.replaceParents(2L, "doc.md", parents(0, "别人的块"));

        // 入库层在策略不产父块时以此覆盖清除历史父块(如策略切换/重处理)
        store.replaceParents(1L, "doc.md", Map.of());

        assertEquals(Map.of(), store.findParents(1L, List.of(0)));
        assertEquals(Map.of(0, "别人的块"), store.findParents(2L, List.of(0)));
    }

    @Test
    void findParentsWithEmptySeqMakesNoQuery() {
        assertEquals(Map.of(), store.findParents(1L, List.of()));
        verifyNoInteractions(mapper);
    }

    @Test
    void writeFailureIsSwallowedNotThrown() {
        // 父块写失败仅 WARN: 小块向量照常入库, 不得让父块存储故障把整次入库判失败
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(KnowledgeChunk.class));

        assertDoesNotThrow(() -> store.replaceParents(1L, "doc.md", parents(0, "甲块")));
    }

    @Test
    void readFailureDegradesToEmptyMap() {
        // 读失败返回空: 检索照常按小块文本注入, 不因父块查询失败而报错
        doThrow(new RuntimeException("db down")).when(mapper).selectList(any());

        assertDoesNotThrow(() -> {
            assertEquals(Map.of(), store.findParents(1L, List.of(0)));
        });
    }

    @Test
    void writtenRowsCarryDocSeqContentAndFileName() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        store.replaceParents(7L, "doc.md", parents(3, "父块文本"));

        assertEquals(1, table.size());
        KnowledgeChunk row = table.get(0);
        assertEquals(7L, row.getDocId());
        assertEquals("doc.md", row.getFileName(), "父块行必须冗余存所属文档文件名");
        assertEquals(3, row.getParentSeq());
        assertEquals("父块文本", row.getContent());
        assertTrue(row.getCreatedAt() != null && row.getCreatedAt().isAfter(before),
                "created_at 必须写入(表列 NOT NULL)");
    }
}
