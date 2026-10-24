<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { knowledgeApi } from '../api'
import { formatDateTime, formatFileSize } from '../utils/format'
import { useToastStore } from '../stores/toast'
import Icon from '../components/Icon.vue'
import StatusTag from '../components/StatusTag.vue'
import Modal from '../components/Modal.vue'
import Pagination from '../components/Pagination.vue'

const toast = useToastStore()

/* ---------------- 状态字典（与后端 KnowledgeDocument.status 对齐） ---------------- */
const STATUS = {
  0: { text: '待处理', type: 'warn' },
  1: { text: '处理中', type: 'info' },
  2: { text: '已完成', type: 'success' },
  3: { text: '失败', type: 'danger' },
}

/* ---------------- 上传（单文件直传 / 多文件批量） ---------------- */
const fileInput = ref(null)
const uploading = ref(false)
const progress = ref(0)
// 2026-10-07 后端已去扩展名白名单(魔数嗅探+内容兜底), 前端放开 accept 提示
const acceptExts = '.pdf,.docx,.txt,.md,.java,.py,.js,.ts,.csv,.json,.xml,.html,.sql,.md,.yaml,.yml,.go,.c,.cpp,.cs,.sh'

function pickFile() {
  fileInput.value?.click()
}

async function onFileChange(e) {
  const files = Array.from(e.target.files ?? [])
  e.target.value = ''
  if (!files.length) return
  if (files.length === 1) await doUpload(files[0])
  else await doBatchUpload(files)
}

async function doUpload(file) {
  uploading.value = true
  progress.value = 0
  try {
    const body = await knowledgeApi.upload(file, (p) => (progress.value = p))
    toast.success(body.message || '文档已提交处理')
    await load()
  } catch (err) {
    toast.error(err.message)
  } finally {
    uploading.value = false
  }
}

/* ---------------- 批量上传结果弹窗 ---------------- */
const batchModal = ref(false)
const batchResults = ref([])

const batchOk = computed(() => batchResults.value.filter((r) => r.success).length)
const batchFail = computed(() => batchResults.value.length - batchOk.value)

async function doBatchUpload(files) {
  uploading.value = true
  progress.value = 0
  try {
    const body = await knowledgeApi.uploadBatch(files, (p) => (progress.value = p))
    batchResults.value = body.data || []
    batchModal.value = true
    await load()
  } catch (err) {
    toast.error(err.message)
  } finally {
    uploading.value = false
  }
}

/* ---------------- 筛选与列表 ---------------- */
const filters = reactive({ fileName: '', status: '', startTime: '', endTime: '' })
const list = ref([])
const page = reactive({ num: 1, size: 10, total: 0, totalPages: 0 })
const loading = ref(false)
let pollTimer = null

async function load(keepPage = true) {
  loading.value = true
  try {
    const params = {
      pageNum: page.num,
      pageSize: page.size,
    }
    if (filters.fileName) params.fileName = filters.fileName
    if (filters.status !== '') params.status = filters.status
    if (filters.startTime) params.startTime = filters.startTime
    if (filters.endTime) params.endTime = filters.endTime
    const body = await knowledgeApi.listDocuments(params)
    list.value = body.data.records
    page.total = body.data.total
    page.totalPages = body.data.totalPages
    schedulePoll()
  } catch (e) {
    toast.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 有"待处理/处理中"的文档时每 5 秒自动刷新状态（异步入库回显） */
function schedulePoll() {
  clearInterval(pollTimer)
  const processing = list.value.some((d) => d.status === 0 || d.status === 1)
  if (processing) pollTimer = setInterval(() => load(), 5000)
}

function search() {
  page.num = 1
  load()
}

function resetFilters() {
  filters.fileName = ''
  filters.status = ''
  filters.startTime = ''
  filters.endTime = ''
  search()
}

function changePage(p) {
  page.num = p
  load()
}

/* ---------------- 操作：禁用/启用检索(管理员; 不删向量/索引, 启用零成本恢复) ---------------- */
const togglingId = ref(null)

async function toggleEnabled(doc) {
  togglingId.value = doc.id
  try {
    const body = doc.enabled
      ? await knowledgeApi.disableDocument(doc.id)
      : await knowledgeApi.enableDocument(doc.id)
    doc.enabled = body.data.enabled
    toast.success(body.message || (doc.enabled ? '文档已启用' : '文档已禁用'))
  } catch (e) {
    toast.error(e.message)
  } finally {
    togglingId.value = null
  }
}

/* ---------------- 操作：重新处理 / 删除 ---------------- */
async function reprocess(doc) {
  try {
    await knowledgeApi.reprocess(doc.id)
    toast.success('文档已重新提交处理')
    await load()
  } catch (e) {
    toast.error(e.message)
  }
}

const confirmDelete = ref(false)
const pendingDelete = ref(null)

function askDelete(doc) {
  pendingDelete.value = doc
  confirmDelete.value = true
}

async function doDelete() {
  try {
    await knowledgeApi.deleteDocument(pendingDelete.value.id)
    confirmDelete.value = false
    toast.success('文档已删除')
    await load()
  } catch (e) {
    toast.error(e.message)
  }
}

const hasProcessing = computed(() => list.value.some((d) => d.status === 0 || d.status === 1))

/* ---------------- 查看全文(仅已完成; 每次直查库, 无缓存) ---------------- */
const fullModal = ref(false)
const fullLoading = ref(false)
const fullDoc = ref(null)
const fullData = ref(null)

async function openFullText(doc) {
  if (doc.status !== 2) return
  fullDoc.value = doc
  fullData.value = null
  fullLoading.value = true
  fullModal.value = true
  try {
    const body = await knowledgeApi.getFullText(doc.id)
    fullData.value = body.data
  } catch (e) {
    toast.error(e.message)
    fullModal.value = false
  } finally {
    fullLoading.value = false
  }
}

onMounted(() => load())
onUnmounted(() => clearInterval(pollTimer))
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <div class="title"><Icon name="database" :size="18" style="color: var(--accent)" /> 知识库管理</div>
        <div class="sub">上传文本文档（PDF/DOCX/代码/CSV/JSON 等，≤50MB，归档/可执行除外），异步入库后用于 RAG 检索</div>
      </div>
      <button class="btn primary" :disabled="uploading" @click="pickFile">
        <span v-if="uploading" class="spinner" />
        <Icon v-else name="upload" :size="15" />
        {{ uploading ? `上传中 ${progress}%` : '上传文档（可多选）' }}
      </button>
      <input
        ref="fileInput"
        type="file"
        :accept="acceptExts"
        multiple
        style="display: none"
        @change="onFileChange"
      />
    </header>

    <div class="page-body">
      <!-- 筛选 -->
      <div class="filter-bar">
        <input
          v-model="filters.fileName"
          class="input"
          placeholder="文件名模糊搜索"
          style="width: 200px"
          @keyup.enter="search"
        />
        <select v-model="filters.status" class="select">
          <option value="">全部状态</option>
          <option v-for="(meta, k) in STATUS" :key="k" :value="k">{{ meta.text }}</option>
        </select>
        <span class="filter-label">创建时间</span>
        <input v-model="filters.startTime" type="date" class="input" style="width: 150px" />
        <span class="filter-label">至</span>
        <input v-model="filters.endTime" type="date" class="input" style="width: 150px" />
        <button class="btn primary sm" @click="search"><Icon name="search" :size="13" /> 查询</button>
        <button class="btn sm" @click="resetFilters">重置</button>
        <button class="btn sm ghost" :disabled="loading" title="刷新列表" @click="load()">
          <Icon name="refresh" :size="13" />
        </button>
        <span v-if="hasProcessing" class="polling-tip"><span class="spinner" style="width: 11px; height: 11px" /> 有文档入库中，每 5 秒自动刷新</span>
      </div>

      <!-- 列表 -->
      <div class="table-wrap">
        <table class="data">
          <thead>
            <tr>
              <th>文件名</th>
              <th>类型</th>
              <th>大小</th>
              <th>分块数</th>
              <th>状态</th>
              <th>错误信息</th>
              <th>上传人 ID</th>
              <th>创建时间</th>
              <th style="width: 150px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="doc in list" :key="doc.id">
              <td class="cell-strong">
                <span style="display: inline-flex; align-items: center; gap: 7px">
                  <Icon name="file" :size="14" style="color: var(--fg-faint)" />
                  <span
                    class="cell-truncate"
                    :class="{ 'name-link': doc.status === 2 }"
                    style="max-width: 240px"
                    :title="doc.status === 2 ? '点击查看全文' : doc.fileName"
                    @click="openFullText(doc)"
                  >{{ doc.fileName }}</span>
                </span>
              </td>
              <td class="mono">{{ doc.fileType || '-' }}</td>
              <td>{{ formatFileSize(doc.fileSize) }}</td>
              <td>{{ doc.chunkCount ?? '-' }}</td>
              <td>
                <StatusTag :type="STATUS[doc.status]?.type || 'neutral'" :text="STATUS[doc.status]?.text || doc.status" />
              </td>
              <td>
                <span class="cell-truncate" :title="doc.errorMessage" :style="{ color: 'var(--danger)', fontSize: '12px' }">
                  {{ doc.errorMessage || '-' }}
                </span>
              </td>
              <td class="mono">{{ doc.createdBy }}</td>
              <td class="mono" style="font-size: 12px">{{ formatDateTime(doc.createTime) }}</td>
              <td>
                <div style="display: flex; gap: 6px">
                  <button
                    class="toggle-btn"
                    :class="doc.enabled === false ? 'off' : 'on'"
                    :disabled="doc.status !== 2 || togglingId === doc.id"
                    :title="doc.enabled === false ? '点击启用：恢复语义/BM25 两路召回(不重新向量化)' : '点击禁用：两路检索不再召回该文档, 不删向量/索引, 可随时再启用'"
                    @click="toggleEnabled(doc)"
                  >
                    <span class="state-dot" />
                    {{ doc.enabled === false ? '已禁用' : '启用中' }}
                  </button>
                  <button class="btn sm" :disabled="doc.status === 0 || doc.status === 1" title="清理旧向量后重新入库" @click="reprocess(doc)">
                    <Icon name="refresh" :size="12" /> 重处理
                  </button>
                  <button class="btn sm danger" @click="askDelete(doc)">
                    <Icon name="trash" :size="12" /> 删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!list.length && !loading" class="empty">
          <Icon name="inbox" :size="30" class="icon" />
          <p>暂无文档</p>
          <p class="hint">点击右上角「上传文档」添加第一个知识文件，或检查筛选条件</p>
        </div>
      </div>

      <Pagination
        :page="page.num"
        :size="page.size"
        :total="page.total"
        :total-pages="page.totalPages"
        @change="changePage"
      />
    </div>

    <Modal v-model="confirmDelete" title="删除文档" :width="440">
      <p style="color: var(--fg-dim)">
        确认删除 <strong>{{ pendingDelete?.fileName }}</strong> 吗？
        将同时清理向量库中的分块与本地文件，不可恢复。
      </p>
      <template #footer>
        <button class="btn" @click="confirmDelete = false">取消</button>
        <button class="btn danger" @click="doDelete"><Icon name="trash" :size="14" /> 删除</button>
      </template>
    </Modal>

    <!-- 批量上传结果：逐文件成败 -->
    <Modal v-model="batchModal" title="批量上传结果" :width="540">
      <p class="batch-summary">
        共 {{ batchResults.length }} 个文件：成功 <strong style="color: var(--success)">{{ batchOk }}</strong> 个<template v-if="batchFail">，失败 <strong style="color: var(--danger)">{{ batchFail }}</strong> 个（失败不影响其它文件）</template><template v-else>，全部已提交入库</template>
      </p>
      <div class="batch-list">
        <div v-for="(r, i) in batchResults" :key="i" class="batch-row">
          <Icon name="file" :size="14" class="batch-ico" />
          <span class="batch-name" :title="r.fileName">{{ r.fileName }}</span>
          <StatusTag :type="r.success ? 'success' : 'danger'" :text="r.success ? '已提交' : '失败'" />
          <span class="batch-msg" :title="r.success ? `docId ${r.docId}` : r.message">
            {{ r.success ? `docId ${r.docId} · 排队入库中` : r.message }}
          </span>
        </div>
      </div>
      <template #footer>
        <button class="btn primary" @click="batchModal = false">好的</button>
      </template>
    </Modal>

    <!-- 文档全文：等宽展示 + 内部滚动；存量文档无全文时给出生成指引 -->
    <Modal v-model="fullModal" :title="fullDoc?.fileName || '文档全文'" :width="760">
      <div v-if="fullLoading" class="full-loading"><span class="spinner" /> 正在读取全文…</div>
      <div v-else-if="!fullData?.available" class="empty" style="padding: 26px 0">
        <Icon name="inbox" :size="26" class="icon" />
        <p>该文档暂无全文</p>
        <p class="hint">2026-10-12 前入库的存量文档需「重新处理」后生成全文</p>
      </div>
      <template v-else>
        <div class="full-meta">
          <span class="mono">{{ fullData.fileType || '-' }}</span>
          <span>共 {{ fullData.charCount.toLocaleString() }} 字</span>
        </div>
        <pre class="full-pre">{{ fullData.content }}</pre>
      </template>
      <template #footer>
        <button class="btn" @click="fullModal = false">关闭</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.polling-tip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--info);
  margin-left: auto;
}

/* 文件名 → 查看全文入口(仅已完成文档挂载该类) */
.name-link {
  cursor: pointer;
  transition: color var(--speed);
}
.name-link:hover {
  color: var(--accent);
  text-decoration: underline;
}

/* 全文弹窗 */
.full-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 30px 0;
  font-size: 13px;
  color: var(--fg-muted);
}
.full-meta {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--fg-muted);
  margin-bottom: 10px;
}
.full-pre {
  max-height: 62vh;
  overflow: auto;
  margin: 0;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-deep);
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 批量上传结果弹窗 */
.batch-summary { font-size: 13px; color: var(--fg-dim); margin-bottom: 12px; }
.batch-list { display: flex; flex-direction: column; gap: 6px; max-height: 46vh; overflow: auto; }
.batch-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-deep);
}
.batch-ico { color: var(--fg-faint); flex-shrink: 0; }
.batch-name {
  flex-shrink: 0;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
  color: var(--fg);
  font-weight: 500;
}
.batch-msg {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  color: var(--fg-muted);
}
.toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--speed);
  white-space: nowrap;
}
.toggle-btn.on {
  background: var(--success-dim);
  border: 1px solid var(--success);
  color: var(--success);
}
.toggle-btn.on:hover {
  background: var(--success);
  color: #fff;
}
.toggle-btn.off {
  background: var(--bg-deep);
  border: 1px solid var(--border);
  color: var(--fg-muted);
}
.toggle-btn.off:hover {
  border-color: var(--warn);
  color: var(--warn);
}
.toggle-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.state-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

</style>
