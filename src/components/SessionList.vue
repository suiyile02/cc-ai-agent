<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sessionApi } from '../api'
import { useChatStore } from '../stores/chat'
import { useToastStore } from '../stores/toast'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

/**
 * 会话记录列表（挂在最左侧主侧栏内）。
 * - 按创建日期分组：今天 / 昨天 / 最近 7 天 / 更早
 * - 接收关键字做本地过滤（标题或 sessionId 模糊匹配，覆盖已加载的 50 条）
 * - 选择会话时若不在对话页则自动跳转
 */
const props = defineProps({
  keyword: { type: String, default: '' },
})

const chat = useChatStore()
const toast = useToastStore()
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const confirmDelete = ref(false)
const pendingDelete = ref(null)
const confirmRename = ref(false)
const pendingRename = ref(null)
const renameValue = ref('')
/** 与后端 @Size(max=200) 同口径 */
const TITLE_MAX = 200

const GROUPS = [
  { key: 'today', label: '今天' },
  { key: 'yesterday', label: '昨天' },
  { key: 'week', label: '最近 7 天' },
  { key: 'earlier', label: '更早' },
]

const DAY = 24 * 3600 * 1000

function groupKey(createTime) {
  if (!createTime) return 'earlier'
  const d = new Date(String(createTime).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return 'earlier'
  const now = new Date()
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const t = d.getTime()
  if (t >= startToday) return 'today'
  if (t >= startToday - DAY) return 'yesterday'
  if (t >= startToday - 6 * DAY) return 'week'
  return 'earlier'
}

const grouped = computed(() => {
  const kw = props.keyword.trim().toLowerCase()
  const filtered = chat.sessions.filter((s) => {
    if (!kw) return true
    const title = (s.title || '未命名会话').toLowerCase()
    return title.includes(kw) || (s.sessionId || '').toLowerCase().includes(kw)
  })
  return GROUPS.map((g) => ({
    ...g,
    items: filtered.filter((s) => groupKey(s.createTime) === g.key),
  })).filter((g) => g.items.length)
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const body = await sessionApi.list({ pageNum: 1, pageSize: 50 })
    chat.setSessions(body.data.records, body.data.total)
    if (!chat.currentSessionId && chat.sessions.length) {
      chat.select(chat.sessions[0].sessionId)
    }
  } catch (e) {
    toast.error(`会话加载失败：${e.message}`)
  } finally {
    loading.value = false
  }
}

function selectSession(s) {
  if (route.name !== 'chat') router.push({ name: 'chat' })
  chat.select(s.sessionId)
}

async function archiveSession(s) {
  try {
    await sessionApi.archive(s.id)
    await load()
    toast.success('会话已归档')
  } catch (e) {
    toast.error(e.message)
  }
}

function askDelete(s) {
  pendingDelete.value = s
  confirmDelete.value = true
}

/**
 * 打开改名框。自动生成的标题不满意时可以覆盖——
 * 后端自动标题只在会话首轮触发一次，改名后不会再被覆盖。
 */
function askRename(s) {
  pendingRename.value = s
  renameValue.value = s.title || ''
  confirmRename.value = true
}

async function saveRename() {
  const title = renameValue.value.trim()
  if (!title) {
    toast.error('标题不能为空')
    return
  }
  try {
    const body = await sessionApi.rename(pendingRename.value.id, title)
    chat.patchSession(pendingRename.value.id, { title: body.data.title })
    confirmRename.value = false
    toast.success('标题已更新')
  } catch (e) {
    toast.error(e.message)
  }
}

async function doDelete() {
  try {
    await sessionApi.remove(pendingDelete.value.id)
    const wasCurrent = chat.currentSessionId === pendingDelete.value.sessionId
    chat.removeSession(pendingDelete.value.id)
    chat.clearMessages(pendingDelete.value.sessionId)
    if (wasCurrent) chat.currentSessionId = chat.sessions[0]?.sessionId || ''
    confirmDelete.value = false
    toast.success('会话已删除')
  } catch (e) {
    toast.error(e.message)
  }
}
</script>

<template>
  <div class="session-list">
    <template v-if="grouped.length">
      <template v-for="g in grouped" :key="g.key">
        <div class="group-label">{{ g.label }}</div>
        <button
          v-for="s in g.items"
          :key="s.id"
          class="session-item"
          :class="{ active: s.sessionId === chat.currentSessionId }"
          :title="`归属 userId: ${s.userId} · sessionId: ${s.sessionId}`"
          @click="selectSession(s)"
        >
          <span class="session-title">{{ s.title || '未命名会话' }}</span>
          <span class="ops" @click.stop>
            <span class="op" title="改名" role="button" tabindex="0" @click="askRename(s)"><Icon name="pencil" :size="13" /></span>
            <span class="op" title="归档" role="button" tabindex="0" @click="archiveSession(s)"><Icon name="archive" :size="13" /></span>
            <span class="op danger" title="删除" role="button" tabindex="0" @click="askDelete(s)"><Icon name="trash" :size="13" /></span>
          </span>
        </button>
      </template>
    </template>

    <div v-else-if="keyword.trim()" class="list-empty">未找到匹配的会话</div>
    <div v-else-if="!loading" class="list-empty">
      <Icon name="inbox" :size="22" class="icon" />
      <span>还没有会话</span>
    </div>
    <div v-if="loading" class="list-empty"><span class="spinner" /> 加载中…</div>

    <Modal v-model="confirmRename" title="修改会话标题" :width="420">
      <label class="field">
        <span class="field-label">标题</span>
        <input
          v-model="renameValue"
          class="input"
          :maxlength="TITLE_MAX"
          placeholder="给这个会话起个名字"
          @keyup.enter="saveRename"
        />
      </label>
      <p class="rename-tip">新会话的第一轮会由后端自动生成标题（先截断提问占位，再由模型概括精修）；改成人工标题后不会再被覆盖。</p>
      <template #footer>
        <button class="btn" @click="confirmRename = false">取消</button>
        <button class="btn primary" @click="saveRename"><Icon name="check" :size="14" /> 保存</button>
      </template>
    </Modal>

    <Modal v-model="confirmDelete" title="删除会话" :width="400">
      <p style="color: var(--fg-dim)">
        确认删除会话 <strong class="mono">{{ pendingDelete?.sessionId }}</strong> 吗？
        删除后对话记忆同时清空，不可恢复。
      </p>
      <template #footer>
        <button class="btn" @click="confirmDelete = false">取消</button>
        <button class="btn danger" @click="doDelete"><Icon name="trash" :size="14" /> 删除</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.session-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 8px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.group-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--fg-muted);
  padding: 10px 10px 4px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  cursor: pointer;
  color: var(--fg-dim);
  font-size: 13px;
  text-align: left;
  transition: background var(--speed);
  flex-shrink: 0;
}
.session-item:hover { background: var(--surface-2); }
.session-item.active {
  background: var(--surface);
  color: var(--fg);
  font-weight: 500;
  box-shadow: var(--shadow-card);
}
.session-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops { display: none; gap: 2px; flex-shrink: 0; }
.session-item:hover .ops { display: inline-flex; }
.op {
  width: 22px; height: 22px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 5px; color: var(--fg-faint); cursor: pointer;
}
.op:hover { background: var(--muted); color: var(--fg); }
.op.danger:hover { color: var(--danger); }

.list-empty {
  padding: 28px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--fg-faint);
  font-size: 12px;
  text-align: center;
}
.list-empty .icon { opacity: 0.5; }

/* .input 是全局样式，但 .field 系列只在登录页 scoped 过，这里按同一口径补一份 */
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12.5px; color: var(--fg-muted); }
.field .input { height: 38px; }
.rename-tip { margin: 10px 0 0; font-size: 12px; line-height: 1.6; color: var(--fg-faint); }
</style>
