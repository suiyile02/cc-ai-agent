<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { systemApi } from '../api'
import { formatDateTime, formatDuration, prettyJson, truncate } from '../utils/format'
import { outcomeTag } from '../utils/outcome'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import Icon from '../components/Icon.vue'
import StatusTag from '../components/StatusTag.vue'
import Modal from '../components/Modal.vue'
import Pagination from '../components/Pagination.vue'

const auth = useAuthStore()
const toast = useToastStore()

/* ---------------- 四个日志页签的元信息 ---------------- */
const TABS = [
  { key: 'chat', label: '对话日志', icon: 'chat' },
  { key: 'tool', label: '工具调用', icon: 'zap' },
  { key: 'rag', label: 'RAG 决策', icon: 'search' },
  { key: 'ctx', label: '上下文装配', icon: 'sliders' },
]
const activeTab = ref('chat')

/* ---------------- 筛选（四页签共用 sessionId/userId/时间，外加各自的） ---------------- */
const filters = reactive({
  sessionId: '',
  userId: '',
  startTime: '',
  endTime: '',
  toolName: '',
  toolStatus: '',
  ragMode: '',
})

// 出口标签口径见 utils/outcome.js（与检索调试页共用）；旧的 ragMode 只是关键词表的事前预判，
// 保留为灰色参考列，用来对照"预判 vs 实际出口"的偏差。

const list = ref([])
const page = reactive({ num: 1, size: 10, total: 0, totalPages: 0 })
const loading = ref(false)

const TOOL_STATUS = {
  SUCCESS: { text: 'SUCCESS', type: 'success' },
  FAILED: { text: 'FAILED', type: 'danger' },
}

function baseParams() {
  const p = { pageNum: page.num, pageSize: page.size }
  if (filters.sessionId) p.sessionId = filters.sessionId
  // 非管理员传了也会被后端强制改写为本人，这里直接禁用入口
  if (auth.isAdmin && filters.userId) p.userId = filters.userId
  if (filters.startTime) p.startTime = filters.startTime
  if (filters.endTime) p.endTime = filters.endTime
  return p
}

async function load() {
  loading.value = true
  try {
    let body
    if (activeTab.value === 'chat') {
      body = await systemApi.chatLogs(baseParams())
    } else if (activeTab.value === 'tool') {
      const p = baseParams()
      if (filters.toolName) p.toolName = filters.toolName
      if (filters.toolStatus) p.status = filters.toolStatus
      body = await systemApi.toolCallLogs(p)
    } else if (activeTab.value === 'rag') {
      const p = baseParams()
      if (filters.ragMode) p.ragMode = filters.ragMode
      body = await systemApi.ragDecisions(p)
    } else {
      body = await systemApi.contextLogs(baseParams())
    }
    list.value = body.data.records
    page.total = body.data.total
    page.totalPages = body.data.totalPages
  } catch (e) {
    toast.error(e.message)
    list.value = []
  } finally {
    loading.value = false
  }
}

function search() {
  page.num = 1
  load()
}

function changePage(p) {
  page.num = p
  load()
}

watch(activeTab, () => {
  page.num = 1
  list.value = []
  load()
})

onMounted(load)

/* ---------------- 行详情（JSON 弹窗） ---------------- */
const detail = ref(null)
const detailOpen = ref(false)
const detailTitle = ref('')

function openDetail(row, title) {
  detail.value = row
  detailTitle.value = title
  detailOpen.value = true
}

const detailJson = computed(() => (detail.value ? prettyJson(JSON.stringify(detail.value)) : ''))

/* ---------------- 问答模式热切换(管理员按钮; 纯内存, 重启回退严格基线) ---------------- */
const kbOnly = ref(null) // null=未加载; true=严格知识库 / false=宽松自由作答
const modeLoading = ref(false)
const confirmModeOpen = ref(false)
const confirmModeTarget = ref(null)

async function loadMode() {
  try {
    kbOnly.value = (await systemApi.kbOnlyStatus()).data
  } catch {
    // 模式徽标加载失败不影响日志页主体, 静默(后端降级口径一致)
  }
}

function askSwitch(target) {
  confirmModeTarget.value = target
  confirmModeOpen.value = true
}

async function doSwitch() {
  modeLoading.value = true
  try {
    kbOnly.value = (await systemApi.switchKbOnly(confirmModeTarget.value)).data
    toast.success(
      kbOnly.value
        ? '已切换为严格知识库模式：无据时拒答/交由工具裁决'
        : '已切换为宽松自由作答：无据时模型自由发挥（重启后自动回退严格）'
    )
  } catch (e) {
    toast.error(e.message)
  } finally {
    modeLoading.value = false
    confirmModeOpen.value = false
  }
}

onMounted(loadMode)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <div class="title"><Icon name="logs" :size="18" style="color: var(--accent)" /> 系统日志</div>
        <div class="sub">
          授权规则：管理员可查全量；普通用户固定只看本人数据（后端强制改写 userId）
        </div>
      </div>
      <div class="header-right">
        <!-- 问答模式徽标 + 管理员热切换按钮 -->
        <div v-if="kbOnly !== null" class="mode-box" :class="{ loose: !kbOnly }">
          <Icon name="sliders" :size="13" />
          <span class="mode-label" :title="kbOnly ? '无据时拒答/交由工具裁决，禁止模型自由发挥' : '无据时模型自由作答，可能引入自身知识'">
            {{ kbOnly ? '严格知识库模式' : '宽松自由作答' }}
          </span>
          <button
            v-if="auth.isAdmin"
            class="btn sm ghost"
            :disabled="modeLoading"
            @click="askSwitch(!kbOnly)"
          >
            <Icon name="refresh" :size="12" />
            切换为{{ kbOnly ? '宽松' : '严格' }}
          </button>
          <span v-if="auth.isAdmin" class="mode-hint">重启回退严格基线</span>
        </div>
        <button class="btn sm ghost" :disabled="loading" @click="load">
          <Icon name="refresh" :size="13" /> 刷新
        </button>
      </div>
    </header>

    <div class="page-body">
      <!-- 页签 -->
      <div class="tabs" role="tablist">
        <button
          v-for="t in TABS"
          :key="t.key"
          class="tab"
          :class="{ active: activeTab === t.key }"
          role="tab"
          :aria-selected="activeTab === t.key"
          @click="activeTab = t.key"
        >
          <Icon :name="t.icon" :size="13" />
          {{ t.label }}
        </button>
      </div>

      <!-- 筛选 -->
      <div class="filter-bar">
        <input v-model="filters.sessionId" class="input mono" placeholder="sessionId" style="width: 190px" @keyup.enter="search" />
        <input
          v-if="auth.isAdmin"
          v-model.number="filters.userId"
          class="input mono"
          placeholder="userId（可空）"
          style="width: 130px"
          @keyup.enter="search"
        />
        <template v-if="activeTab === 'tool'">
          <input v-model="filters.toolName" class="input mono" placeholder="工具名如 queryEmployee" style="width: 190px" @keyup.enter="search" />
          <select v-model="filters.toolStatus" class="select">
            <option value="">全部状态</option>
            <option value="SUCCESS">SUCCESS</option>
            <option value="FAILED">FAILED</option>
          </select>
        </template>
        <template v-if="activeTab === 'rag'">
          <select v-model="filters.ragMode" class="select">
            <option value="">全部意图</option>
            <option value="KB">KB（走知识库）</option>
            <option value="GENERAL">GENERAL（自由问答）</option>
          </select>
        </template>
        <span class="filter-label">时间</span>
        <input v-model="filters.startTime" type="date" class="input" style="width: 145px" />
        <span class="filter-label">至</span>
        <input v-model="filters.endTime" type="date" class="input" style="width: 145px" />
        <button class="btn primary sm" @click="search"><Icon name="search" :size="13" /> 查询</button>
        <button
          class="btn sm"
          @click="filters.sessionId = ''; filters.userId = ''; filters.toolName = ''; filters.toolStatus = ''; filters.ragMode = ''; filters.startTime = ''; filters.endTime = ''; search()"
        >重置</button>
      </div>

      <!-- 表格区 -->
      <div v-if="loading" class="table-wrap"><div class="empty" style="padding: 60px"><span class="spinner" /><p>加载中…</p></div></div>

      <!-- 对话日志 -->
      <div v-else-if="activeTab === 'chat'" class="table-wrap">
        <table class="data">
          <thead>
            <tr><th>ID</th><th>会话</th><th>用户</th><th>用户消息</th><th>助手回复</th><th>模型</th><th>Tokens</th><th>耗时</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id" class="clickable" title="点击查看完整 JSON" @click="openDetail(r, `对话日志 #${r.id}`)">
              <td class="mono">{{ r.id }}</td>
              <td class="mono cell-truncate" style="max-width: 130px" :title="r.sessionId">{{ r.sessionId }}</td>
              <td class="mono">{{ r.userId }}</td>
              <td class="cell-truncate" :title="r.userMessage">{{ truncate(r.userMessage, 34) }}</td>
              <td class="cell-truncate" :title="r.assistantReply">{{ truncate(r.assistantReply, 44) }}</td>
              <td class="mono" style="font-size: 11.5px">{{ r.modelName || '-' }}</td>
              <td class="mono">{{ r.totalTokens ?? '-' }}</td>
              <td>{{ formatDuration(r.durationMs) }}</td>
              <td class="mono" style="font-size: 11.5px">{{ formatDateTime(r.createTime) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!list.length" class="empty"><Icon name="inbox" :size="28" class="icon" /><p>暂无对话日志</p></div>
      </div>

      <!-- 工具调用日志 -->
      <div v-else-if="activeTab === 'tool'" class="table-wrap">
        <table class="data">
          <thead>
            <tr><th>ID</th><th>工具名</th><th>状态</th><th>入参</th><th>出参</th><th>耗时</th><th>错误</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id" class="clickable" title="点击查看完整 JSON" @click="openDetail(r, `工具调用 #${r.id}`)">
              <td class="mono">{{ r.id }}</td>
              <td class="mono cell-strong">{{ r.toolName }}</td>
              <td><StatusTag :type="TOOL_STATUS[r.status]?.type || 'neutral'" :text="r.status" /></td>
              <td class="mono cell-truncate" :title="r.inputParams">{{ truncate(r.inputParams, 30) }}</td>
              <td class="mono cell-truncate" :title="r.outputResult">{{ truncate(r.outputResult, 36) }}</td>
              <td>{{ formatDuration(r.durationMs) }}</td>
              <td><span class="cell-truncate" style="color: var(--danger); font-size: 12px" :title="r.errorMessage">{{ r.errorMessage || '-' }}</span></td>
              <td class="mono" style="font-size: 11.5px">{{ formatDateTime(r.createTime) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!list.length" class="empty"><Icon name="inbox" :size="28" class="icon" /><p>暂无工具调用日志</p><p class="hint">Agent 模式下提问业务数据（如“张三在哪个部门”）即产生工具日志</p></div>
      </div>

      <!-- RAG 决策日志 -->
      <div v-else-if="activeTab === 'rag'" class="table-wrap">
        <table class="data">
          <thead>
            <tr><th>ID</th><th>用户消息</th><th>出口</th><th>预判</th><th>会话类型</th><th>执行检索</th><th>语义/关键词/最终命中</th><th>TopK / 阈值 / 语义最高分</th><th>重排</th><th>耗时</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id" class="clickable" title="点击查看完整 JSON" @click="openDetail(r, `RAG 决策 #${r.id}`)">
              <td class="mono">{{ r.id }}</td>
              <td class="cell-truncate" :title="r.userMessage">{{ truncate(r.userMessage, 26) }}</td>
              <td><StatusTag :type="outcomeTag(r.answerOutcome).type" :text="outcomeTag(r.answerOutcome).text" /></td>
              <td class="mono" style="font-size: 11.5px; color: var(--fg-faint)">{{ r.ragMode || '—' }}</td>
              <td class="mono" style="font-size: 11.5px">{{ r.sessionType }}</td>
              <td><StatusTag :type="r.retrievalExecuted ? 'info' : 'neutral'" :text="r.retrievalExecuted ? '是' : '否'" :dot="false" /></td>
              <td class="mono">{{ r.semanticHits }} / {{ r.keywordHits }} / <strong style="color: var(--accent)">{{ r.finalHits }}</strong></td>
              <td class="mono">{{ r.topK }} / {{ r.similarityThreshold }} /
                <span :style="{ color: r.semanticMaxScore != null && r.semanticMaxScore < r.similarityThreshold ? 'var(--warn)' : 'var(--accent)' }">{{ r.semanticMaxScore == null ? '—' : r.semanticMaxScore.toFixed(3) }}</span>
              </td>
              <td class="mono" style="font-size: 11.5px">{{ r.rerankMode }}</td>
              <td>{{ formatDuration(r.durationMs) }}</td>
              <td class="mono" style="font-size: 11.5px">{{ formatDateTime(r.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!list.length" class="empty"><Icon name="inbox" :size="28" class="icon" /><p>暂无 RAG 决策日志</p></div>
      </div>

      <!-- 上下文装配日志 -->
      <div v-else class="table-wrap">
        <table class="data">
          <thead>
            <tr><th>ID</th><th>用户消息</th><th>改写后查询</th><th>意图</th><th>Token 分布（系统/历史/摘要/RAG/用户）</th><th>合计</th><th>截断</th><th>耗时</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id" class="clickable" title="点击查看完整 JSON" @click="openDetail(r, `上下文装配 #${r.id}`)">
              <td class="mono">{{ r.id }}</td>
              <td class="cell-truncate" :title="r.userMessage">{{ truncate(r.userMessage, 24) }}</td>
              <td class="cell-truncate mono" :title="r.rewrittenQuery" style="font-size: 11.5px">{{ r.rewrittenQuery ? truncate(r.rewrittenQuery, 20) : '-' }}</td>
              <td><StatusTag :type="r.intentMode === 'KB' ? 'success' : 'neutral'" :text="r.intentMode" /></td>
              <td class="mono" style="font-size: 11.5px">{{ r.systemTokens }} / {{ r.historyTokens }} / {{ r.summaryTokens }} / {{ r.ragTokens }} / {{ r.userTokens }}</td>
              <td class="mono cell-strong">{{ r.totalTokens }}</td>
              <td><StatusTag :type="r.truncated ? 'warn' : 'neutral'" :text="r.truncated ? '已截断' : '未截断'" :dot="false" /></td>
              <td>{{ formatDuration(r.durationMs) }}</td>
              <td class="mono" style="font-size: 11.5px">{{ formatDateTime(r.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!list.length" class="empty"><Icon name="inbox" :size="28" class="icon" /><p>暂无上下文装配日志</p></div>
      </div>

      <Pagination
        :page="page.num"
        :size="page.size"
        :total="page.total"
        :total-pages="page.totalPages"
        @change="changePage"
      />
    </div>

    <Modal v-model="detailOpen" :title="detailTitle" :width="680">
      <pre class="detail-json mono">{{ detailJson }}</pre>
    </Modal>

    <!-- 问答模式切换确认(影响所有用户的回答口径) -->
    <Modal v-model="confirmModeOpen" :title="`确认切换为${confirmModeTarget ? '严格知识库模式' : '宽松自由作答'}？`" :width="440">
      <p class="mode-confirm-text">
        <template v-if="confirmModeTarget">
          切换后：无据可依时将<b>固定拒答或交由业务工具裁决</b>，禁止模型自由发挥。立即对所有用户生效。
        </template>
        <template v-else>
          切换后：无据可依时<b>模型将自由作答</b>，可能引入自身知识（可能过时/错误）。立即对所有用户生效。
        </template>
      </p>
      <p class="mode-confirm-hint">重启后自动回退严格知识库基线。</p>
      <template #footer>
        <button class="btn sm" @click="confirmModeOpen = false">取消</button>
        <button class="btn primary sm" :disabled="modeLoading" @click="doSwitch">
          {{ modeLoading ? '切换中…' : '确认切换' }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.tabs {
  display: inline-flex;
  gap: 3px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 3px;
  margin-bottom: 16px;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 14px;
  border: none;
  background: transparent;
  color: var(--fg-muted);
  font-size: 12.5px;
  border-radius: 4px;
  cursor: pointer;
  transition: all var(--speed);
}
.tab:hover { color: var(--fg); }
.tab.active { background: var(--muted); color: var(--accent); font-weight: 600; }

.detail-json {
  margin: 0;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 14px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--fg-dim);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 60vh;
  overflow: auto;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mode-box {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-deep);
  color: var(--accent);
  font-size: 12.5px;
}
.mode-box.loose {
  color: var(--warn);
  border-color: var(--warn);
}
.mode-label {
  font-weight: 600;
  white-space: nowrap;
}
.mode-hint {
  color: var(--fg-faint);
  font-size: 11px;
  white-space: nowrap;
}
.mode-confirm-text {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--fg);
}
.mode-confirm-text b {
  color: var(--warn);
}
.mode-confirm-hint {
  margin: 0;
  font-size: 12px;
  color: var(--fg-faint);
}
</style>
