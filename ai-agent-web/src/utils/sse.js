/**
 * 流式对话客户端。
 * 后端 POST /api/ai/chat/stream 返回 SSE（EventSource 只支持 GET，
 * 所以这里用 fetch + ReadableStream 手工解析 `data:` 帧，末帧为 `data:[DONE]`）。
 */
import { kickToLogin } from '../api'
import { MSG_NET_UNREACHABLE, MSG_AUTH_EXPIRED, MSG_REQUEST_FAILED } from './error-msg'

/**
 * 发起流式问答。
 * @param {object} opts
 * @param {string} opts.sessionId 会话 ID
 * @param {string} opts.message   用户消息
 * @param {string} opts.token     JWT
 * @param {(chunk: string) => void} opts.onChunk 每收到一段增量文本
 * @param {() => void} [opts.onDone]  流正常结束
 * @param {AbortSignal} [opts.signal] 中断用（停止生成按钮）
 * @returns {Promise<void>} 异常时 reject（401/凭证失效已统一踢回登录页）
 */
export async function streamChat({ sessionId, message, token, onChunk, onDone, signal }) {
  let res
  try {
    res = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ sessionId, message }),
      signal,
    })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    const err = new Error(MSG_NET_UNREACHABLE)
    err.offline = true
    throw err
  }

  if (!res.ok) {
    if (res.status === 401) {
      // 凭证失效与 REST 路径同策略：踢回登录，文案不含任何后端细节
      kickToLogin()
      throw new Error(MSG_AUTH_EXPIRED)
    }
    // 代理/网关返回的 5xx 视为后端不可达；后端自身的错误响应带 message
    const offline = res.status >= 500
    let msg = offline ? MSG_NET_UNREACHABLE : MSG_REQUEST_FAILED
    if (!offline) {
      try {
        const body = await res.json()
        if (body?.message) msg = body.message
      } catch { /* 非 JSON 响应体，用默认消息 */ }
    }
    const err = new Error(msg)
    err.offline = offline
    throw err
  }
  if (!res.body) throw new Error('当前浏览器不支持流式响应')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''

  const dispatch = (rawEvent) => {
    // 一个 SSE 事件可能有多行 data:，按规范拼接为 \n
    const data = rawEvent
      .split('\n')
      .filter((l) => l.startsWith('data:'))
      .map((l) => l.slice(5).replace(/^ /, ''))
      .join('\n')
    if (!data) return
    if (data === '[DONE]') return true
    onChunk(data)
    return false
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const event = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      if (dispatch(event) === true) { onDone?.(); return }
    }
  }
  // 服务端未发 [DONE] 就关流（如异常中断），也视为结束
  onDone?.()
}
