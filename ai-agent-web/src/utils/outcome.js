/**
 * 作答出口（ChatOutcome）的展示口径，系统日志与检索调试页共用一份，避免两处文案漂移。
 * 语义见后端 com.ai.rag.ChatOutcome。
 */
export const OUTCOME_TAGS = {
  ANSWERED_FROM_KB: { text: '知识库作答', type: 'success', hint: '向量路有分块过阈值，按知识库资料作答' },
  ANSWERED_FROM_CACHE: { text: '缓存作答', type: 'success', hint: '命中语义缓存，跳过上下文装配与模型调用' },
  REFUSED_NO_EVIDENCE: { text: '无据拒答', type: 'warn', hint: '无可信知识库依据，严格模式下按固定口径友好拒答' },
  ANSWERED_OPEN: { text: '自由作答', type: 'info', hint: '非检索会话或检索降级，交给模型自身知识作答' },
  TOOL_DATA: { text: '工具数据', type: 'info', hint: '工具轮次，答案来自业务库实时查询' },
}

/**
 * 取出口标签。
 *
 * @param {string|null} outcome 后端 answerOutcome
 * @returns {{text: string, type: string, hint: string}} 标签定义（未知值原样展示）
 */
export function outcomeTag(outcome) {
  if (!outcome) return { text: '—', type: 'neutral', hint: '该轮未产生出口记录' }
  return OUTCOME_TAGS[outcome] || { text: outcome, type: 'neutral', hint: '' }
}
