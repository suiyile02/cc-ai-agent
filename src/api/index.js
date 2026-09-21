import axios from 'axios'
import router from '../router'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { forceOfflineLogout } from '../utils/auth-guard'

/**
 * 统一 HTTP 客户端。
 * 后端所有接口返回 {code, message, data, timestamp}（code=200 成功），
 * 401/6005 表示未登录或凭证失效，统一踢回登录页。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 120_000,
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
  return config
})

function kickToLogin() {
  const auth = useAuthStore()
  // 彻底清除本地登录态与聊天缓存，避免换账号后残留上一个用户的会话
  auth.logout()
  useChatStore().$reset()
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login', query: { expired: '1' } })
  }
}

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) return body
      if (body.code === 6005 || res.status === 401) kickToLogin()
      const err = new Error(body.message || `请求失败（code=${body.code}）`)
      err.code = body.code
      throw err
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const body = err.response?.data
    if (status === 401) { kickToLogin() }

    // 后端不可达判定：网络层错误，或 HTTP 5xx 且响应体不是后端统一格式
    // （后端自身的错误一定带 {code,message}；代理连不上后端时返回的 5xx 没有该字段）
    const backendStyle = body && typeof body === 'object' && 'code' in body
    const unreachable =
      err.code === 'ERR_NETWORK' || (!backendStyle && status >= 500)
    if (unreachable) forceOfflineLogout()

    let msg
    if (unreachable) msg = '无法连接服务器，请确认后端（9090 端口）已启动'
    else if (err.code === 'ECONNABORTED') msg = '请求超时，请稍后重试'
    else msg = body?.message || err.message || '网络异常'
    const e = new Error(msg)
    e.code = body?.code
    e.httpStatus = status
    e.offline = unreachable
    throw e
  },
)

/* ================= 鉴权 ================= */
export const authApi = {
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  me: () => http.get('/auth/me'),
}

/* ================= 会话 ================= */
export const sessionApi = {
  create: (data) => http.post('/sessions', data),
  list: (params) => http.get('/sessions', { params }),
  /** 单个会话详情（首轮对话后刷新自动标题用） */
  detail: (id) => http.get(`/sessions/${id}`),
  /** 手动改名（人工标题优先，之后不会被自动标题覆盖） */
  rename: (id, title) => http.put(`/sessions/${id}/title`, { title }),
  /** 历史消息回显（后端 2026-09 新增，旧版后端未重启会 404） */
  messages: (id) => http.get(`/sessions/${id}/messages`),
  archive: (id) => http.put(`/sessions/${id}/archive`),
  remove: (id) => http.delete(`/sessions/${id}`),
}

/* ================= 对话 ================= */
export const chatApi = {
  chat: (data) => http.post('/ai/chat', data),
  ragSearch: (data) => http.post('/ai/rag/search', data),
  // 流式走 utils/sse.js（fetch 流）
}

/* ================= 知识库 ================= */
export const knowledgeApi = {
  upload: (file, onProgress) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/knowledge/upload', form, {
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
  },
  /** 批量上传：字段名 files（多个），返回逐文件成败列表（后端 2026-09 新增） */
  uploadBatch: (files, onProgress) => {
    const form = new FormData()
    for (const f of files) form.append('files', f)
    return http.post('/knowledge/upload/batch', form, {
      onUploadProgress: (e) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
  },
  listDocuments: (params) => http.get('/knowledge/documents', { params }),
  deleteDocument: (id) => http.delete(`/knowledge/documents/${id}`),
  reprocess: (id) => http.post(`/knowledge/documents/${id}/reprocess`),
}

/* ================= 系统日志 ================= */
export const systemApi = {
  chatLogs: (params) => http.get('/system/chat-logs', { params }),
  toolCallLogs: (params) => http.get('/system/tool-call-logs', { params }),
  ragDecisions: (params) => http.get('/system/rag-decisions', { params }),
  contextLogs: (params) => http.get('/system/context-logs', { params }),
}
