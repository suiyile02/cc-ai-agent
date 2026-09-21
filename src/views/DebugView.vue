<script setup>
import { reactive, ref } from 'vue'
import { chatApi } from '../api'
import { formatScore } from '../utils/format'
import { useToastStore } from '../stores/toast'
import Icon from '../components/Icon.vue'

const toast = useToastStore()

const form = reactive({ question: '', topK: 5, threshold: 0.3 })
const loading = ref(false)
const hits = ref(null) // null=未查询过, []=无命中
const elapsed = ref(null)

async function run() {
  if (!form.question.trim()) {
    toast.error('请输入要检索的问题')
    return
  }
  loading.value = true
  hits.value = null
  const t0 = performance.now()
  try {
    const body = await chatApi.ragSearch({
      question: form.question.trim(),
      topK: form.topK,
      similarityThreshold: form.threshold,
    })
    hits.value = body.data || []
    elapsed.value = Math.round(performance.now() - t0)
  } catch (e) {
    toast.error(e.message)
  } finally {
    loading.value = false
  }
}

function scoreColor(score) {
  if (score == null) return 'var(--fg-faint)'
  if (score >= 0.5) return 'var(--accent)'
  if (score >= 0.3) return 'var(--warn)'
  return 'var(--danger)'
}
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <div class="title"><Icon name="sliders" :size="18" style="color: var(--accent)" /> RAG 检索调试</div>
        <div class="sub">直接调用 /api/ai/rag/search 查看知识库命中与相似度，不受意图路由影响</div>
      </div>
    </header>

    <div class="page-body debug-layout">
      <!-- 左：查询参数 -->
      <div class="card panel">
        <h3 class="panel-title">查询参数</h3>
        <label class="field">
          <span class="field-label">问题</span>
          <textarea
            v-model="form.question"
            class="textarea"
            rows="4"
            placeholder="例如：入职满两年的员工有多少天年假？"
            @keydown.ctrl.enter="run"
          />
        </label>
        <label class="field">
          <span class="field-label">topK（召回条数）：<strong class="mono" style="color: var(--accent)">{{ form.topK }}</strong></span>
          <input v-model.number="form.topK" type="range" min="1" max="20" step="1" />
        </label>
        <label class="field">
          <span class="field-label">相似度阈值：<strong class="mono" style="color: var(--accent)">{{ form.threshold.toFixed(2) }}</strong></span>
          <input v-model.number="form.threshold" type="range" min="0" max="1" step="0.05" />
        </label>
        <button class="btn primary run-btn" :disabled="loading" @click="run">
          <span v-if="loading" class="spinner" />
          <Icon v-else name="search" :size="14" />
          {{ loading ? '检索中…' : '执 行 检 索' }}
        </button>
        <p class="tip">
          调参建议：答不上来时先调低阈值或加大 topK 看是否有命中；
          命中很多但回答不准时调高阈值过滤低分噪声。
        </p>
      </div>

      <!-- 右：命中结果 -->
      <div class="results">
        <div v-if="hits === null" class="empty" style="padding-top: 90px">
          <Icon name="search" :size="30" class="icon" />
          <p>输入问题并执行检索</p>
          <p class="hint">命中结果将按相似度从高到低展示（文件名 / 分块序号 / 分数 / 内容片段）</p>
        </div>

        <div v-else-if="!hits.length" class="empty" style="padding-top: 90px">
          <Icon name="alert" :size="30" class="icon" />
          <p>无命中结果</p>
          <p class="hint">知识库可能没有相关内容，或相似度都低于阈值 {{ form.threshold.toFixed(2) }} —— 先上传文档或调低阈值试试</p>
        </div>

        <template v-else>
          <div class="results-meta mono">{{ hits.length }} 条命中 · 前端往返 {{ elapsed }} ms</div>
          <div v-for="(h, i) in hits" :key="i" class="card hit">
            <div class="hit-head">
              <span class="rank mono">#{{ i + 1 }}</span>
              <Icon name="file" :size="13" />
              <span class="mono hit-file">{{ h.fileName }}</span>
              <span class="mono hit-chunk">chunk {{ h.chunkIndex }}</span>
              <span class="hit-score mono" :style="{ color: scoreColor(h.score) }">{{ formatScore(h.score) }}</span>
            </div>
            <div class="score-track">
              <div class="score-bar" :style="{ width: `${Math.min((h.score ?? 0) * 100, 100)}%`, background: scoreColor(h.score) }" />
            </div>
            <p class="hit-snippet">{{ h.snippet || '（无片段预览）' }}</p>
            <div class="hit-doc mono">doc_id: {{ h.documentId }}</div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.debug-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}
.panel {
  width: 340px;
  flex-shrink: 0;
  padding: 20px;
  position: sticky;
  top: 0;
}
.panel-title { font-size: 13.5px; font-weight: 600; margin-bottom: 16px; color: var(--fg); }
.field { display: flex; flex-direction: column; gap: 7px; margin-bottom: 16px; }
.field-label { font-size: 12.5px; color: var(--fg-muted); }
.run-btn { width: 100%; height: 38px; }
.tip {
  margin-top: 14px;
  font-size: 11.5px;
  color: var(--fg-faint);
  line-height: 1.8;
  border-top: 1px dashed var(--border);
  padding-top: 12px;
}

.results { flex: 1; min-width: 0; }
.results-meta { font-size: 11.5px; color: var(--fg-faint); margin-bottom: 12px; }
.hit { padding: 14px 16px; margin-bottom: 12px; }
.hit-head { display: flex; align-items: center; gap: 8px; }
.rank { color: var(--fg-faint); font-size: 11px; }
.hit-file { font-size: 12.5px; color: var(--fg); }
.hit-chunk { font-size: 11px; color: var(--fg-faint); }
.hit-score { margin-left: auto; font-size: 12.5px; font-weight: 600; }
.score-track {
  height: 3px;
  background: var(--muted);
  border-radius: 2px;
  margin: 9px 0 10px;
  overflow: hidden;
}
.score-bar { height: 100%; border-radius: 2px; transition: width 0.3s ease; }
.hit-snippet { font-size: 12.5px; color: var(--fg-muted); line-height: 1.7; white-space: pre-wrap; }
.hit-doc { margin-top: 8px; font-size: 10.5px; color: var(--fg-faint); }

@media (max-width: 900px) {
  .debug-layout { flex-direction: column; }
  .panel { width: 100%; position: static; }
}
</style>
