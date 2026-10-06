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
const acceptExts = '.pdf,.docx,.txt,.md'

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
async function toggleEnabled(doc) {
  try {
    const body = doc.enabled
      ? await knowledgeApi.disableDocument(doc.id)
      : await knowledgeApi.enableDocument(doc.id)
    doc.enabled = body.data.enabled
    toast.success(body.message || (doc.enabled ? '文档已启用' : '文档已禁用'))
  } catch (e) {
    toast.error(e.message)
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

onMounted(() => load())
onUnmounted(() => clearInterval(pollTimer))
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <div class="title"><Icon name="database" :size="18" style="color: var(--accent)" /> 知识库管理</div>
        <div class="sub">上传 PDF / DOCX / TXT / MD（≤50MB），异步入库后用于 RAG 检索</div>
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
                  <span class="cell-truncate" style="max-width: 240px" :title="doc.fileName">{{ doc.fileName }}</span>
                </span>
              </td>
              <td class="mono">{{ doc.fileType || '-' }}</td>
              <td>{{ formatFileSize(doc.fileSize) }}</td>
              <td>{{ doc.chunkCount ?? '-' }}</td>
              <td>
                <StatusTag :type="STATUS[doc.status]?.type || 'neutral'" :text="STATUS[doc.status]?.text || doc.status" />
                <StatusTag v-if="doc.enabled === false" type="warn" text="已禁用检索" style="margin-left: 6px" />
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
                    class="btn sm"
                    :disabled="doc.status !== 2"
                    :title="doc.enabled === false ? '恢复语义/BM25 两路召回(不重新向量化)' : '两路检索不再召回该文档, 不删向量/索引, 可随时启用'"
                    @click="toggleEnabled(doc)"
                  >
                    <Icon name="sliders" :size="12" /> {{ doc.enabled === false ? '启用检索' : '禁用检索' }}
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
</style>
