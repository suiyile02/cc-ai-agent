package com.ai.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程内关键词索引(BM25)，作为多路召回中的“关键词路”。
 *
 * <p>文档入库成功后由知识库模块调用 {@link #addDocument} 注册全部分块，
 * 删除/重处理时调用 {@link #removeDocument} 同步清理。
 * 分词策略：英文/数字按词元，中文按“连续二元组 + 单字”近似(无需外部分词器)。
 *
 * <p>注意：索引保存在进程内存(与外部向量库 Qdrant 相互独立)；应用重启后由
 * {@link KeywordIndexRebuilder} 从 Qdrant payload 自动重建(不重新向量化, 秒级完成)。
 */
@Component
public class KeywordIndex {

    /** 单个分块(倒排索引的最小单元) */
    public record Chunk(String key, Long docId, String fileName, Integer chunkIndex,
                        String text, int length) {
    }

    /** 一次检索的命中 */
    public record Hit(String key, Long docId, String fileName, Integer chunkIndex,
                      String text, double score) {
    }

    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9_]+");
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** chunkKey -> 分块 */
    private final Map<String, Chunk> chunksById = new LinkedHashMap<>();
    /** term -> chunkKey -> 词频 tf */
    private final Map<String, Map<String, Integer>> postings = new HashMap<>();
    /** docId -> chunkKeys(用于按文档整批删除) */
    private final Map<Long, List<String>> keysByDoc = new HashMap<>();
    /** 读写锁: 检索并发读, 入库/删除独占写(替代全方法 synchronized 的全局串行) */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    /** 全部分块长度之和(avgLen 增量维护, 替代每次检索 O(n) 全量重算) */
    private long totalChunkLength = 0;

    /**
     * 注册一个文档的全部分块到关键词索引(幂等：先清理旧内容再写入)。
     *
     * @param docId     知识库文档 ID(knowledge_document.id)
     * @param fileName  来源文件名
     * @param chunkDocs 入库分块(按顺序, chunk_index 取列表下标)
     */
    public void addDocument(Long docId, String fileName, List<Document> chunkDocs) {
        if (chunkDocs == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            addDocumentLocked(docId, fileName, chunkDocs);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 内部实现(调用方必须已持写锁) */
    private void addDocumentLocked(Long docId, String fileName, List<Document> chunkDocs) {
        removeDocumentLocked(docId);
        int index = 0;
        for (Document doc : chunkDocs) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                index++;
                continue;
            }
            String key = keyOf(docId, index);
            chunksById.put(key, new Chunk(key, docId, fileName, index, text, text.length()));
            totalChunkLength += text.length();
            keysByDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(key);
            for (String term : termsOf(text)) {
                postings.computeIfAbsent(term, t -> new HashMap<>())
                        .merge(key, 1, Integer::sum);
            }
            index++;
        }
    }

    /**
     * 删除某文档在索引中的全部分块(删除/重处理时调用)。
     *
     * @param docId 知识库文档 ID
     */
    public void removeDocument(Long docId) {
        lock.writeLock().lock();
        try {
            removeDocumentLocked(docId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 内部实现(调用方必须已持写锁) */
    private void removeDocumentLocked(Long docId) {
        List<String> keys = keysByDoc.remove(docId);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            Chunk chunk = chunksById.remove(key);
            if (chunk == null) {
                continue;
            }
            totalChunkLength -= chunk.length();
            for (String term : termsOf(chunk.text())) {
                Map<String, Integer> tf = postings.get(term);
                if (tf == null) {
                    continue;
                }
                tf.remove(key);
                if (tf.isEmpty()) {
                    postings.remove(term);
                }
            }
        }
    }

    /**
     * 当前索引中的分块总数(可用于能力可用性判断)。
     *
     * @return 分块数
     */
    /** 【仅测试引用】生产可用性判断统一用 {@link #isEmpty()}; size() 仅供单元测试断言。 */
    public int size() {
        lock.readLock().lock();
        try {
            return chunksById.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 索引是否为空(空则关键词路不可用)。
     *
     * @return true=无任何已索引分块
     */
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return chunksById.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * BM25 检索：按得分降序返回 Top-K 命中。
     *
     * @param query 查询文本
     * @param topK  返回条数上限
     * @return 命中列表(得分>0), 无命中返回空列表
     */
    public List<Hit> search(String query, int topK) {
        lock.readLock().lock();
        try {
            return searchLocked(query, topK);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 内部实现(调用方必须已持读锁) */
    private List<Hit> searchLocked(String query, int topK) {
        if (query == null || query.isBlank() || chunksById.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = new ArrayList<>(termsOf(query));
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        int totalDocs = chunksById.size();
        double avgLen = totalDocs == 0 ? 1.0 : (double) totalChunkLength / totalDocs;

        Map<String, Double> scores = new HashMap<>();
        for (String term : queryTerms) {
            Map<String, Integer> tfByKey = postings.get(term);
            if (tfByKey == null || tfByKey.isEmpty()) {
                continue;
            }
            double df = tfByKey.size();
            double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
            for (Map.Entry<String, Integer> e : tfByKey.entrySet()) {
                Chunk chunk = chunksById.get(e.getKey());
                if (chunk == null) {
                    continue;
                }
                double tf = e.getValue();
                double denom = tf + K1 * (1 - B + B * chunk.length() / Math.max(avgLen, 1));
                scores.merge(e.getKey(), idf * tf * (K1 + 1) / denom, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(0, topK))
                .map(e -> {
                    Chunk c = chunksById.get(e.getKey());
                    return new Hit(c.key(), c.docId(), c.fileName(), c.chunkIndex(),
                            c.text(), e.getValue());
                })
                .toList();
    }

    /**
     * 构造分块主键。
     *
     * @param docId      文档 ID
     * @param chunkIndex 分块序号
     * @return "docId:chunkIndex" 形式的主键
     */
    static String keyOf(Long docId, int chunkIndex) {
        return docId + ":" + chunkIndex;
    }

    /**
     * 轻量分词：英文/数字词元 + 中文连续二元组(单字也成词, 便于短查询命中)。
     *
     * @param text 待分词文本
     * @return 词元列表(可为空)
     */
    static List<String> termsOf(String text) {
        List<String> terms = new ArrayList<>();
        String lower = text.toLowerCase();
        Matcher m = LATIN_TOKEN.matcher(lower);
        while (m.find()) {
            terms.add(m.group());
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                terms.add(String.valueOf(c));
                if (i + 1 < lower.length() && isCjk(lower.charAt(i + 1))) {
                    terms.add(lower.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    /**
     * 判断字符是否属于中日韩统一表意文字区(用于中文分词)。
     *
     * @param c 字符
     * @return true=CJK
     */
    private static boolean isCjk(char c) {
        return (c >= '\u4e00' && c <= '\u9fff')
                || (c >= '\u3400' && c <= '\u4dbf')
                || (c >= '\uf900' && c <= '\ufaff');
    }
}
