package com.ai.knowledge.ingestion;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import com.ai.knowledge.ParentBlockStore;
import com.ai.knowledge.chunking.ChunkingStrategy;
import com.ai.knowledge.chunking.ChunkingStrategyRouter;
import com.ai.knowledge.chunking.DocumentChunker;
import com.ai.knowledge.chunking.MdStructureChunker;
import com.ai.knowledge.chunking.TableRowChunker;
import com.ai.knowledge.entity.KnowledgeDocument;
import com.ai.knowledge.mapper.KnowledgeDocumentMapper;
import com.ai.knowledge.parsing.DocumentTextCleaner;
import com.ai.knowledge.parsing.MimeResolver;
import com.ai.knowledge.parsing.TextExtractor;
import com.ai.knowledge.storage.FileStorageService;
import com.ai.rag.service.KeywordIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentIngestionOrchestrator} 父子文档接线测试: 走真实解析链路(Tika 读文件 → 清洗 →
 * 结构策略切分 → 聚合父块 → 打标), 验证"策略写 {@code _parent_seq} → 入库层聚合父块写库 →
 * 小块元数据转 {@code parent_ref} 且不残留内部键"的完整接缝。
 *
 * <p>用真实 Tika + 真实 {@link MdStructureChunker}/{@link TableRowChunker} + 真实
 * {@link TextExtractor}/{@link DocumentChunker}/{@link ParentBlockAggregator}/{@link ChunkMetadataEnricher}
 * 而非 mock 策略/阶段: 接缝两端任何一侧的元数据约定漂移(键名/类型/清理时点)只有真实链路能暴露。
 */
class DocumentIngestionOrchestratorTest {

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
    private DocumentIngestionOrchestrator service;

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

        // 真实阶段组件接线, 只有依赖外部能力处用 mock
        service = new DocumentIngestionOrchestrator(documentMapper,
                new TextExtractor(new DocumentTextCleaner(props)),
                new DocumentChunker(mimeResolver, chunkingRouter),
                new ParentBlockAggregator(),
                new ChunkMetadataEnricher(),
                parentBlockStore, fileStorageService, vectorStoreProvider, keywordIndex, props);
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

    @Test
    void oversizeSectionYieldsOneParentDespiteManyChunks() throws Exception {
        // 顺序回归锁: 父块聚合必须在打标(把 _parent_seq 转成 parent_ref)之前执行——
        // 曾因顺序颠倒而读不到内部键, 退化为"每块一个父块"; 节序号与块序号重合的用例掩盖了它,
        // 这里一个超长节切成多块, 父块数必须仍是 1。
        Path file = Files.createTempFile("ingest-oversize", ".md");
        StringBuilder md = new StringBuilder("# 大节\n");
        for (int i = 0; i < 500; i++) {
            md.append("这是第").append(i).append("句,内容足够长使本节能被再次切分。");
        }
        Files.writeString(file, md.toString());
        KnowledgeDocument d = doc(file, "MD");
        when(documentMapper.selectById(1L)).thenReturn(d);
        when(mimeResolver.resolve(any(), any())).thenReturn("text/markdown");
        when(chunkingRouter.route("text/markdown")).thenReturn(new MdStructureChunker(props));

        service.ingestDocument(1L);

        assertTrue(addedChunks.get(0).size() > 1, "前置确认: 超长节被切成多块");
        assertEquals(1, capturedParents.get(1L).size(),
                "同节所有小块必须聚合成一个父块(顺序颠倒会退化为每块一个父块)");
    }

    @Test
    void csvRowsBecomeChunksWithColumnNamesAndRowGroupParents() throws Exception {
        Path file = Files.createTempFile("ingest-table", ".csv");
        Files.writeString(file, "等级,住宿标准,交通标准\n经理,500元,高铁二等座\n总监,\"800元,含补贴\",飞机经济舱\n");
        KnowledgeDocument d = doc(file, "CSV");
        when(documentMapper.selectById(1L)).thenReturn(d);
        when(mimeResolver.resolve(any(), any())).thenReturn("text/csv");
        when(chunkingRouter.route("text/csv")).thenReturn(new TableRowChunker(props));

        service.ingestDocument(1L);

        // 父块 = 同组数据行(列名内嵌, 引号字段完整), 两行同组 → 一个父块
        Map<Integer, String> parents = capturedParents.get(1L);
        assertEquals(1, parents.size(), "两行数据同属一个行组");
        assertTrue(parents.get(0).contains("住宿标准=500元"), "父块含列名=值");
        assertTrue(parents.get(0).contains("住宿标准=800元,含补贴"), "引号字段在入库链路中完整保留");

        // 小块 = 一行一块, 带 parent_ref, 不带内部键
        List<Document> chunks = addedChunks.get(0);
        assertEquals(2, chunks.size());
        for (Document chunk : chunks) {
            assertEquals(0, chunk.getMetadata().get("parent_ref"));
            assertFalse(chunk.getMetadata().containsKey(ChunkingStrategy.PARENT_SEQ_KEY));
            assertEquals(1L, chunk.getMetadata().get("doc_id"));
        }
        assertTrue(chunks.get(0).getText().contains("等级=经理 | 住宿标准=500元"),
                "行文本必须内嵌列名(检索/BM25 可匹配列名): " + chunks.get(0).getText());
    }

    @Test
    void vectorizationFailureCleansUpWrittenParentsAndMarksFailed() throws Exception {
        // 回归: 父块先于向量化写入, 一旦嵌入失败(如 DashScope 批量 400)文档置 3,
        // 但父块残留在 knowledge_chunk 会让"失败文档仍可被 expandToParents 取到内容"
        Path file = Files.createTempFile("ingest-vec-fail", ".md");
        Files.writeString(file, "# 第一章\n内容。\n# 第二章\n内容。\n");
        KnowledgeDocument d = doc(file, "MD");
        when(documentMapper.selectById(1L)).thenReturn(d);
        when(mimeResolver.resolve(any(), any())).thenReturn("text/markdown");
        when(chunkingRouter.route("text/markdown")).thenReturn(new MdStructureChunker(props));
        doThrow(new RuntimeException("400: batch size is invalid, it should not be larger than 20"))
                .when(vectorStore).add(anyList());

        service.ingestDocument(1L);

        // ① 父块确实先被写入过(否则谈不上清理)
        assertNotNull(capturedParents.get(1L), "前置确认: 向量化前父块已写入");
        // ② 失败后必须清理本次父块(文档置失败不得残留可检索内容)
        verify(parentBlockStore).deleteByDocId(1L);
        // ③ 状态为失败 + 中文错误(不是原始英文)
        assertEquals(3, d.getStatus());
        assertTrue(d.getErrorMessage().contains("文档向量化失败"),
                "error_message 必须是中文兜底而非原始英文: " + d.getErrorMessage());
        assertTrue(!d.getErrorMessage().contains("batch size"), "不得透出原始英文异常");
    }

    @Test
    void successfulIngestionExplicitlyClearsErrorMessage() throws Exception {
        // 回归(2026-10-12 生产): 错误文档重处理成功后 status=2/chunk 都更新了,
        // 但 errorMessage 永久残留旧错误——markProcessing/markDone 的 setErrorMessage(null)
        // 被 MyBatis-Plus updateById 的空值策略(忽略 null 字段)吞掉, 从未落库。
        // 必须在成功路径用 update 显式 set error_message = NULL。
        Path file = Files.createTempFile("ingest-clear-err", ".md");
        Files.writeString(file, "# 第一章\n内容。\n");
        KnowledgeDocument d = doc(file, "MD");
        d.setErrorMessage("400: 旧错误, 应被重处理成功清空");
        when(documentMapper.selectById(1L)).thenReturn(d);
        when(mimeResolver.resolve(any(), any())).thenReturn("text/markdown");
        when(chunkingRouter.route("text/markdown")).thenReturn(new MdStructureChunker(props));

        service.ingestDocument(1L);

        // ① 成功路径必须显式把 error_message 置 NULL(仅 updateById 会因空值策略跳过)
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<KnowledgeDocument>> wrapper =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), wrapper.capture());
        boolean hasClear = wrapper.getAllValues().stream()
                .anyMatch(w -> String.valueOf(w.getSqlSet()).contains("error_message"));
        assertTrue(hasClear, "成功路径必须有一条 update 显式设置 error_message(NULL)");
        // ② 内存对象状态已置成功(回显)
        assertEquals(2, d.getStatus());
        assertEquals(1, d.getChunkCount());
    }
}
