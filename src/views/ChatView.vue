<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { sessionApi } from '../api'
import { streamChat } from '../utils/sse'
import { renderMarkdown } from '../utils/markdown'
import { forceOfflineLogout } from '../utils/auth-guard'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useToastStore } from '../stores/toast'
import Icon from '../components/Icon.vue'

const auth = useAuthStore()
const chat = useChatStore()
const toast = useToastStore()

/* ---------------- 消息输入 ---------------- */
const draft = ref('')
const streamMode = ref(true)
const generating = ref(false)
let abortCtrl = null
const listEl = ref(null)
const SUGGESTIONS = [
  '入职满两年能休几天年假？',
  '张三在哪个部门？',
  '订单 SO20260101001 的状态是什么？',
  '现在几点了？',
]

const messages = computed(() => chat.currentMessages)
const activeSession = computed(() => chat.currentSession)
const historyLoading = computed(() => chat.currentBucket?.loadingHistory || false)

/** 选中会话后拉取服务端历史消息回显（每个会话只拉一次；仅展示消息，不展示摘要） */
async function loadHistory(sid) {
  const bucket = chat.ensureMessages(sid)
  if (bucket.loaded || bucket.loadingHistory) return
  const s = chat.sessions.find((x) => x.sessionId === sid)
  if (!s) return
  bucket.loadingHistory = true
  try {
    const body = await sessionApi.messages(s.id)
    bucket.items = (body.data.messages || []).map((m) => ({
      role: m.role,
      content: m.content,
    }))
    bucket.loaded = true
    scrollBottom()
  } catch (e) {
    // 拉取失败（如后端未重启没有该接口）不阻塞新对话，仅提示一次
    bucket.loaded = true
    toast.error(`历史消息加载失败：${e.message}`)
  } finally {
    bucket.loadingHistory = false
  }
}

/** 复制服务端会话 ID（联调 curl 用） */
async function copySessionId() {
  try {
    await navigator.clipboard.writeText(activeSession.value.sessionId)
    toast.success('已复制 sessionId')
  } catch {
    toast.error('复制失败，请手动复制')
  }
}

/* ---------------- 发送消息（同步 / SSE 流式） ---------------- */
/**
 * 首轮结束后刷新一次标题。
 * 后端在回答开始前就已同步写入截断标题，模型精修与回答并行，
 * 所以这一趟拉取通常直接拿到精修版；若精修慢了一步，下次进列表自然是对的。
 */
async function refreshTitle(sessionId) {
  const s = chat.sessions.find((x) => x.sessionId === sessionId)
  if (!s) return
  try {
    const body = await sessionApi.detail(s.id)
    chat.patchSession(s.id, { title: body.data.title })
  } catch {
    /* 标题刷新失败不值得打扰用户 */
  }
}

async function send(text) {
  const content = (text ?? draft.value).trim()
  if (!content || generating.value) return
  if (!activeSession.value) {
    toast.error('请先点击左侧「开始新对话」')
    return
  }
  const sid = activeSession.value.sessionId
  const firstTurn = !activeSession.value.title
  draft.value = ''

  chat.pushMessage(sid, { role: 'user', content })
  chat.pushMessage(sid, { role: 'assistant', content: '', pending: true })
  scrollBottom()

  generating.value = true
  try {
    if (streamMode.value) {
      abortCtrl = new AbortController()
      await streamChat({
        sessionId: sid,
        message: content,
        token: auth.token,
        signal: abortCtrl.signal,
        onChunk: (chunk) => {
          chat.appendToLast(sid, chunk)
          scrollBottom()
        },
      })
      chat.setLastMeta(sid, { pending: false, error: null })
    } else {
      const { default: api } = await import('../api')
      const body = await api.chatApi.chat({ sessionId: sid, message: content })
      chat.setLastMeta(sid, {
        content: body.data.content,
        sources: body.data.sources || [],
        pending: false,
      })
      scrollBottom()
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      const items = chat.ensureMessages(sid).items
      chat.setLastMeta(sid, { pending: false, content: (items.at(-1)?.content || '') + '\n\n（已手动停止）' })
    } else if (e.offline) {
      // 后端不可达：强制退出回登录页（与全局策略一致）
      forceOfflineLogout()
    } else {
      chat.setLastMeta(sid, { pending: false, error: e.message })
    }
  } finally {
    generating.value = false
    abortCtrl = null
    // 即使本轮出错或被手动停止，后端也已经写入了兜底标题，值得一刷
    if (firstTurn) refreshTitle(sid)
  }
}

function stop() {
  abortCtrl?.abort()
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function scrollBottom() {
  nextTick(() => {
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(() => chat.currentSessionId, (sid) => {
  scrollBottom()
  if (sid) loadHistory(sid)
})
</script>

<template>
  <div class="chat-main">
    <header v-if="activeSession" class="chat-head">
      <strong class="head-title">{{ activeSession.title || '未命名会话' }}</strong>
      <span class="uid-badge mono" title="会话归属的用户 ID——应与当前登录账号一致；不一致说明登录态错位，请退出重登">uid {{ activeSession.userId }}</span>
      <button class="sid-chip mono" title="服务端会话 ID（联调 curl 用），点击复制" @click="copySessionId">
        {{ activeSession.sessionId }}
        <Icon name="copy" :size="11" />
      </button>
    </header>

    <div ref="listEl" class="msg-list">
      <div class="chat-col">
        <!-- 历史回显加载中 -->
        <div v-if="historyLoading" class="history-loading"><span class="spinner" /> 正在拉取历史消息…</div>

        <!-- 欢迎页 -->
        <div v-if="!messages.length && !historyLoading" class="chat-welcome">
          <h2>你好，我是智能问答助手</h2>
          <p class="welcome-sub">知识库问题自动走 RAG 检索，员工 / 订单等业务数据由 Agent 工具查询</p>
          <div class="chips">
            <button v-for="q in SUGGESTIONS" :key="q" class="chip" @click="send(q)">{{ q }}</button>
          </div>
        </div>

        <template v-for="(m, i) in messages" :key="i">
          <!-- 用户消息：右侧气泡 -->
          <div v-if="m.role === 'user'" class="row user">
            <div class="user-bubble">{{ m.content }}</div>
          </div>

          <!-- 助手消息：左侧纯文本 -->
          <div v-else class="row ai">
            <span class="ai-avatar"><Icon name="bot" :size="14" /></span>
            <div class="ai-content">
              <div v-if="m.error" class="msg-error">
                <Icon name="alert" :size="14" />
                {{ m.error }}
              </div>
              <template v-else>
                <div class="md" v-html="renderMarkdown(m.content) || '<span class=placeholder>…</span>'" />
                <span v-if="m.pending" class="typing-cursor" />
              </template>

              <!-- 引用来源 -->
              <details v-if="m.sources && m.sources.length" class="sources">
                <summary>
                  <Icon name="database" :size="12" />
                  引用 {{ m.sources.length }} 个知识库来源
                </summary>
                <div v-for="(src, j) in m.sources" :key="j" class="source-item">
                  <div class="source-head">
                    <Icon name="file" :size="12" />
                    <span class="mono source-name">{{ src.fileName }}</span>
                    <span v-if="src.score != null" class="mono source-score">{{ src.score.toFixed(4) }}</span>
                  </div>
                  <p v-if="src.snippet" class="source-snippet">{{ src.snippet }}</p>
                </div>
              </details>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 输入区：居中圆角大框 -->
    <footer class="composer-wrap">
      <div class="composer">
        <textarea
          v-model="draft"
          class="composer-input"
          rows="2"
          :placeholder="activeSession ? '给智能助手发送消息，Enter 发送，Shift+Enter 换行' : '请先点击左侧「开始新对话」'"
          :disabled="!activeSession || generating"
          @keydown="onKeydown"
        />
        <div class="composer-bar">
          <label class="stream-switch" title="开启后逐字流式输出（SSE），关闭则同步等待完整回答">
            <input v-model="streamMode" type="checkbox" />
            <span>流式输出</span>
          </label>
          <button v-if="generating" class="stop-btn" title="停止生成" @click="stop">
            <Icon name="stop" :size="14" />
          </button>
          <button v-else class="send-btn" :disabled="!draft.trim() || !activeSession" aria-label="发送" @click="send()">
            <Icon name="send" :size="15" />
          </button>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat-main {
  height: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.head-title { font-size: 14px; color: var(--fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.uid-badge {
  font-size: 10.5px;
  color: var(--fg-muted);
  background: var(--muted);
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.sid-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 10px;
  margin-left: auto;
  background: transparent;
  border: 1px dashed var(--border-strong);
  border-radius: 999px;
  color: var(--fg-faint);
  font-size: 11px;
  cursor: copy;
  transition: all var(--speed);
  white-space: nowrap;
  overflow: hidden;
  max-width: 230px;
}
.sid-chip:hover { color: var(--accent); border-color: var(--accent); }

.msg-list { flex: 1; overflow: auto; }
.chat-col { max-width: 780px; margin: 0 auto; padding: 28px 24px 8px; }

.history-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 26px 0 10px;
  color: var(--fg-muted);
  font-size: 12.5px;
}

.chat-welcome {
  text-align: center;
  padding-top: 13vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.chat-welcome h2 { font-size: 22px; font-weight: 600; color: var(--fg); }
.welcome-sub { color: var(--fg-muted); font-size: 13px; }
.chips { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; margin-top: 16px; }
.chip {
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 13px;
  color: var(--fg-dim);
  cursor: pointer;
  transition: all var(--speed);
}
.chip:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-dim); }

.row { margin-bottom: 26px; }
.row.user { display: flex; justify-content: flex-end; }
.user-bubble {
  max-width: 78%;
  background: var(--bubble);
  color: var(--fg);
  border-radius: 16px;
  padding: 10px 16px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.row.ai { display: flex; gap: 12px; }
.ai-avatar {
  width: 28px; height: 28px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%;
  background: var(--accent-dim); color: var(--accent);
  margin-top: 3px;
}
.ai-content {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  line-height: 1.75;
  color: var(--fg);
  padding-top: 2px;
}
.placeholder { color: var(--fg-faint); }
.msg-error { display: flex; align-items: center; gap: 8px; color: var(--danger); font-size: 13px; }

/* 引用来源 */
.sources { margin-top: 10px; }
.sources summary {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--fg-muted); cursor: pointer;
  list-style: none; user-select: none;
}
.sources summary::-webkit-details-marker { display: none; }
.source-item {
  margin-top: 8px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px 12px;
}
.source-head { display: flex; align-items: center; gap: 6px; color: var(--fg-muted); }
.source-name { font-size: 11.5px; color: var(--fg-dim); }
.source-score { margin-left: auto; font-size: 10.5px; color: var(--accent); }
.source-snippet {
  margin-top: 5px; font-size: 12px; color: var(--fg-muted);
  max-height: 66px; overflow: hidden; line-height: 1.6;
}

/* 输入区：居中圆角大框 */
.composer-wrap { display: flex; justify-content: center; padding: 8px 24px 20px; flex-shrink: 0; }
.composer {
  width: 100%;
  max-width: 780px;
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: 22px;
  padding: 12px 14px 10px;
  box-shadow: 0 4px 14px rgba(16, 24, 40, 0.06);
  transition: border-color var(--speed), box-shadow var(--speed);
}
.composer:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--ring); }
.composer-input {
  width: 100%;
  border: none;
  background: transparent;
  resize: none;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg);
  padding: 2px 4px;
  min-height: 48px;
  max-height: 160px;
  outline: none;
}
.composer-input::placeholder { color: var(--fg-faint); }
.composer-input:disabled { opacity: 0.6; }
.composer-bar { display: flex; align-items: center; gap: 12px; margin-top: 4px; }
.stream-switch {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--fg-muted); cursor: pointer; user-select: none;
}
.stream-switch input { accent-color: var(--accent); cursor: pointer; }
.stop-btn {
  width: 34px; height: 34px;
  margin-left: auto;
  border-radius: 50%;
  border: 1px solid rgba(229, 72, 77, 0.4);
  background: var(--surface);
  color: var(--danger);
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: all var(--speed);
}
.stop-btn:hover { background: var(--danger-dim); }
.send-btn {
  width: 34px; height: 34px;
  margin-left: auto;
  border-radius: 50%;
  border: none;
  background: var(--accent);
  color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer;
  transition: all var(--speed);
}
.send-btn:hover:not(:disabled) { background: var(--accent-hover); }
.send-btn:disabled { background: var(--muted); color: var(--fg-faint); cursor: not-allowed; }

@media (max-width: 760px) {
  .chat-col { padding: 20px 14px 8px; }
}
</style>
