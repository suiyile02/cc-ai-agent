package com.ai.chat.dto;

import com.ai.rag.SourceVO;

import java.util.List;

/**
 * RAG 检索调试响应：命中块 + 各路计数 + <b>对话出口预测</b>。
 *
 * <p>存在意义是把"页面看到的分数"和"对话据以判断的分数"放进同一个响应：
 * {@code sources[].score} 是融合分(相对名次，单路榜首恒为 1.0)，只有 {@code semanticMaxScore}
 * 与 {@code sources[].semanticScore} 是出口判据使用的原始余弦分。
 *
 * @param sources            命中来源(按融合分降序, Top-K 内)
 * @param answerOutcome      这轮若交给对话，会命中的出口(同 {@code com.ai.rag.ChatOutcome})
 * @param retrievalQuery     实际用于检索的问题(可能经改写/短查询扩展)
 * @param expanded           是否做过短查询扩展
 * @param executed           检索是否真实执行(向量库与关键词索引都不可用时为 false)
 * @param degraded           检索是否超时/异常降级(降级时的零命中不代表"库里没有")
 * @param topK               生效 Top-K
 * @param similarityThreshold 生效的语义路余弦阈值
 * @param semanticCount      语义路过阈值条数(出口判据的第一个条件)
 * @param keywordCount       关键词路命中条数(小语料下恒有命中，故不作相关性判据)
 * @param semanticMaxScore   语义路阈值过滤前最大余弦分; 未执行检索为 null
 * @param finalHits          最终注入条数
 * @param kbOnly             当前严格知识库模式开关(决定"无据"时是拒答还是自由作答)
 */
public record RagDebugVO(List<SourceVO> sources, String answerOutcome, String retrievalQuery,
                         boolean expanded, boolean executed, boolean degraded,
                         int topK, double similarityThreshold,
                         int semanticCount, int keywordCount, Double semanticMaxScore,
                         int finalHits, boolean kbOnly) {
}
