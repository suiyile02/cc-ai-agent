package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import com.ai.knowledge.ParentBlockStore;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.rag.service.KeywordIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentIngestionService} 父子文档接线测试: 走真实解析链路(Tika 读文件 → 清洗 →
 * 结构策略切分), 验证"策略写 {@code _parent_seq} → 入库层聚合父块写库 → 小块元数据转
 * {@code parent_ref} 且不残留内部键"的完整接缝。
 *
 * <p>用真实 Tika + 真实 {@link MdStructureChunker} 而非 mock 策略: 接缝两端任何一侧的
 * 元数据约定漂移(键名/类型/清理时点)只有真实链路能暴露。
 */
class DocumentIngestionServiceParentTest {

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final MimeResolver mimeResolver = mock(MimeResolver.class);
    private final ChunkingStrategyRouter chunkingRouter = mock(ChunkingStrategyRouter.class);
    private final ParentBlockStore parentBlockStore = mock(ParentBlockStore.class);
    private final KeywordIndex keywordIndex = mock(KeywordIndex.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
    private final VectorStore vectorStore = mock(VectorStore.class);

    /** docId → 入库时写入的父块 */
    private final Map<Long, Map<Integer, String>> capturedParents = new HashMap<>();
    /** 入库时提交给向量库的小块(按调用顺序) */
    private final List<List<Document>> addedChunks = new ArrayList<>();

    private AppProperties props;
    private DocumentIngestionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new AppProperties();
        props.getIngestion().setParseTimeoutMs(30_000);
        IngestionProps.ChunkingStrategyProps sp = new IngestionProps.ChunkingStrategyProps();
        sp.setChunkSize(512);
        props.getIngestion().getChunking().getStrategies().put("md-structure", sp);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(documentMapper.updateById(any(KnowledgeDocument.class))).thenReturn(1);
        when(documentMapper.selectById(anyLong())).thenAnswer(inv -> null);
        doAnswer(inv -> {
            capturedParents.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(parentBlockStore).replaceParents(anyLong(), anyMap());
        doAnswer(inv -> {
            addedChunks.add(new ArrayList<>(inv.getArgument(0)));
            return null;
        }).when(vectorStore).add(anyList());

        service = new DocumentIngestionService(documentMapper, fileStorageService,
                new DocumentTextCleaner(props), mimeResolver, chunkingRouter, parentBlockStore,
                vectorStoreProvider, keywordIndex, props);
    }

    private KnowledgeDocument doc(Path file, String fileType) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(1L);
        d.setFileName(file.getFileName().toString());
        d.setFileType(fileType);
        d.setStoragePath(file.toAbsolutePath().toString());
        d.setCollectionName("default");
        d.setFileHash("hash-x"); // 已就绪, 跳过补填判重键分支
        return d;
    }

    @Test
    void markdownSectionsBecomeParentsAndChunksCarryParentRef() throws Exception {
        Path file = Files.createTempFile("ingest-parent", ".md");
        Files.writeString(file, "# 第一章 总则\n这是总则内容。\n"
                + "## 1.1 适用范围\n适用于全体员工。\n"
                + "# 第二章 附则\n附则内容。\n");
        KnowledgeDocument d = doc(file, "MD");
        when(documentMapper.selectById(1L)).thenReturn(d);
        when(mimeResolver.resolve(any(), any())).thenReturn("text/markdown");
        when(chunkingRouter.route("text/markdown")).thenReturn(new MdStructureChunker(props));

        service.ingestDocument(1L);

        // ① 三个小节 → 三个父块(父块文本 = 同节小块拼接, 含标题)
        Map<Integer, String> parents = capturedParents.get(1L);
        assertNotNull(parents, "结构策略必须触发父块写入");
        assertEquals(3, parents.size(), "三个小节三个父块");
        assertTrue(parents.get(0).contains("# 第一章 总则") && parents.get(0).contains("这是总则内容。"),
                "父块 0 = 第一章整节");
        assertTrue(parents.get(1).contains("## 1.1 适用范围") && parents.get(1).contains("适用于全体员工。"),
                "父块 1 = 1.1 节");
        assertTrue(parents.get(2).contains("# 第二章 附则"), "父块 2 = 第二章");

        // ② 向量库收到的小块: 带 parent_ref(指向父块), 不带内部键 _parent_seq
        List<Document> chunks = addedChunks.get(0);
        assertEquals(3, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> md = chunks.get(i).getMetadata();
            assertEquals(i, md.get("parent_ref"), "第 " + i + " 小块的 parent_ref 应为节序号 " + i);
            assertFalse(md.containsKey(ChunkingStrategy.PARENT_SEQ_KEY),
                    "内部键 _parent_seq 不得泄漏进向量库元数据");
            assertEquals(1L, md.get("doc_id"));
            assertEquals("default", md.get("collection"));
            assertEquals(i, md.get("chunk_index"));
        }
        // ③ 父块文本必须覆盖其小块文本(展开后不丢检索到的内容)
        assertTrue(parents.get(0).contains(chunks.get(0).getText()),
                "父块必须包含命中小块原文");
        assertEquals(2, d.getStatus(), "入库完成状态");
        assertEquals(3, d.getChunkCount());
        assertNull(d.getErrorMessage());
    }

    @Test
    void nonStructuralStrategyClearsHistoricalParents() throws Exception {
        Path file = Files.createTempFile("ingest-flat", ".txt");
        Files.writeString(file, "普通文本一段。\n");
        KnowledgeDocument d = doc(file, "TXT");
        when(documentMapper.selectById(1L)).thenReturn(d);
        when(mimeResolver.resolve(any(), any())).thenReturn("text/plain");
        // 不产父块的策略(如换库/换配置后重处理): 必须显式清空历史父块, 不留过期父块
        ChunkingStrategy flat = new ChunkingStrategy() {
            @Override
            public String name() {
                return "flat-test";
            }

            @Override
            public List<Document> split(Document fullDoc) {
                return List.of(Document.builder().text(fullDoc.getText()).build());
            }
        };
        when(chunkingRouter.route("text/plain")).thenReturn(flat);

        service.ingestDocument(1L);

        assertEquals(Map.of(), capturedParents.get(1L), "非结构策略必须清空历史父块");
        List<Document> chunks = addedChunks.get(0);
        assertFalse(chunks.get(0).getMetadata().containsKey("parent_ref"),
                "无父块的文档不得携带 parent_ref(检索层据此跳过父块查询)");
    }
}
