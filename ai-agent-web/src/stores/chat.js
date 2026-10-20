import { defineStore } from 'pinia'

/** 新建一个会话的消息桶：items 消息列表 / loaded 历史是否已拉取 */
function newBucket() {
  return { items: [], loaded: false, loadingHistory: false }
}

/**
 * 对话状态：会话列表 + 每个会话的消息记录。
 * 消息记录在选中会话时从后端 GET /api/sessions/{id}/messages 回显，
 * 本地发送的消息即时追加；切换会话回来不重复拉取（loaded 标记）。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [],
    sessionsTotal: 0,
    /** sessionId -> { items, loaded, loadingHistory } */
    messagesBySession: {},
    currentSessionId: '',
    streaming: false,
  }),
  getters: {
    currentBucket: (s) => s.messagesBySession[s.currentSessionId] || null,
    currentMessages: (s) => s.messagesBySession[s.currentSessionId]?.items || [],
    currentSession: (s) => s.sessions.find((x) => x.sessionId === s.currentSessionId),
  },
  actions: {
    setSessions(list, total) {
      this.sessions = list
      this.sessionsTotal = total
    },
    upsertSession(session) {
      const i = this.sessions.findIndex((s) => s.id === session.id)
      if (i >= 0) this.sessions.splice(i, 1, session)
      else this.sessions.unshift(session)
      this.sessionsTotal += 1
    },
    /**
     * 就地更新某会话的若干字段（如后端自动生成的标题）。
     * 只改属性不重排列表——重排会让用户正在看的会话跳位。
     */
    patchSession(id, patch) {
      const s = this.sessions.find((x) => x.id === id)
      if (s) Object.assign(s, patch)
    },
    removeSession(id) {
      this.sessions = this.sessions.filter((s) => s.id !== id)
      this.sessionsTotal = Math.max(0, this.sessionsTotal - 1)
    },
    select(sessionId) {
      this.currentSessionId = sessionId
    },
    /** 取（或建）某会话的消息桶 */
    ensureMessages(sessionId) {
      if (!this.messagesBySession[sessionId]) {
        this.messagesBySession[sessionId] = newBucket()
      }
      return this.messagesBySession[sessionId]
    },
    pushMessage(sessionId, msg) {
      this.ensureMessages(sessionId).items.push(msg)
    },
    appendToLast(sessionId, chunk) {
      const bucket = this.ensureMessages(sessionId)
      const last = bucket.items[bucket.items.length - 1]
      if (last) last.content += chunk
    },
    setLastMeta(sessionId, meta) {
      const bucket = this.ensureMessages(sessionId)
      const last = bucket.items[bucket.items.length - 1]
      if (last) Object.assign(last, meta)
    },
    clearMessages(sessionId) {
      delete this.messagesBySession[sessionId]
    },
  },
})
