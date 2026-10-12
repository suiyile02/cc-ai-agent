package com.ai.knowledge.dto;

/**
 * 全库重建结果：切分策略 / 嵌入模型变更后一次性重灌全部文档的汇总。
 *
 * <p>逐篇独立提交（单篇失败不影响其余），异步入库在后台进行——本响应只表示"已提交"，
 * 真实完成情况看文档列表的 status 与 chunk_count。
 *
 * @param submitted 已提交重建的文档数
 * @param total     候选文档总数（排除"处理中"的）
 */
public record ReindexAllVO(int submitted, int total) {
}
