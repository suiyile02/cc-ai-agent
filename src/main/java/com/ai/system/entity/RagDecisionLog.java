package com.ai.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * RAG 意图路由决策日志(rag_decision_log)：每次对话请求自动记录
 * “判定结果(KB/GENERAL)、是否执行检索、多路召回命中数、Top-K/阈值/重排模式、耗时”。
 * 表结构见 db/schema 脚本。
 */
@Getter
@Setter
@TableName("rag_decision_log")
public class RagDecisionLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 */
    private String sessionId;

    /** 所属用户 ID(授权过滤: 管理员或本人可见) */
    private Long userId;

    /** 用户消息(截断保存) */
    private String userMessage;

    /** 意图判定: KB=需要检索 / GENERAL=无需检索 */
    private String ragMode;

    /** 会话类型 RAG/AGENT/HYBRID */
    private String sessionType;

    /** 是否实际执行了检索 */
    private Boolean retrievalExecuted = false;

    /** 语义向量路命中数 */
    private Integer semanticHits = 0;

    /** 关键词(BM25)路命中数 */
    private Integer keywordHits = 0;

    /** 最终注入上下文的命中数 */
    private Integer finalHits = 0;

    /** 检索 Top-K */
    private Integer topK;

    /** 相似度阈值 */
    private Double similarityThreshold;

    /**
     * 语义路**阈值过滤前**的最大相似度(0=该路无结果或分数不可得)。
     *
     * <p>阈值定标(P3-6)与相关性判据(P3-7 四出口)的唯一观察依据: 阈值之后的分数被截断过,
     * 拿它统计必然高估可分性。
     */
    private Double semanticMaxScore;

    /** 重排模式 score/llm/none */
    private String rerankMode;

    /** 检索判定耗时 ms */
    private Integer durationMs;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
