<script setup>
import { computed, reactive, ref } from 'vue'
import { chatApi } from '../api'
import { formatScore } from '../utils/format'
import { outcomeTag } from '../utils/outcome'
import { useToastStore } from '../stores/toast'
import Icon from '../components/Icon.vue'
import StatusTag from '../components/StatusTag.vue'

const toast = useToastStore()

/**
 * 参数缺省一律"跟随对话配置"（请求体里传 null），只有在用户显式勾选覆盖时才下发数值——
 * 此前页面把阈值默认成 0.3 且留空被后端归一为 0，等于凭空多出第三套口径。
 */
const form = reactive({
  question: '',
  topKOverride: false,
  topK: 5,
  thresholdOverride: false,
  threshold: 0.45,
  expand: true,
})
const loading = ref(false)
const result = ref(null) // null=未查询过
const elapsed = ref(null)

const hits = computed(() => (result.value ? result.value.sources : null))
const prediction = computed(() => (result.value ? outcomeTag(result.value.answerOutcome) : null))
/** 语义路一条没过阈值：对话侧会认为"无知识库依据"，与页面是否显示命中无关 */
const noSemanticEvidence = computed(
  () => !!result.value && (result.value.semanticCount ?? 0) === 0)

/** 未勾选覆盖时, 滑杆显示后端回传的实际生效值(避免滑杆停在本地数字上误导) */
const topKView = computed({
  get: () => (form.topKOverride ? form.topK : result.value?.topK ?? form.topK),
  set: (v) => { form.topK = v },
})
const thresholdView = computed({
  get: () => (form.thresholdOverride ? form.threshold : result.value?.similarityThreshold ?? form.threshold),
  set: (v) => { form.threshold = v },
})

async function run() {
  if (!form.question.trim()) {
    toast.error('请输入要检索的问题')
    return
  }
  loading.value = true
  result.value = null
  const t0 = performance.now()
  try {
    const body = await chatApi.ragSearch({
      question: form.question.trim(),
      topK: form.topKOverride ? form.topK : null,
      similarityThreshold: form.thresholdOverride ? form.threshold : null,
      expandShortQuery: form.expand,
    })
    result.value = body.data
    elapsed.value = Math.round(performance.now() - t0)
  } catch (e) {
    toast.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 余弦分色带：过阈值=主色，未过=橙，无语义分=灰 */
function semColor(score) {
  if (score == null) return 'var(--fg-faint)'
  const th = result.value?.similarityThreshold ?? 0
  if (score >= th) return 'var(--accent)'
  if (score >= 0.3) return 'var(--warn)'
  return 'var(--danger)'
}
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <div class="title"><Icon name="sliders" :size="18" style="color: var(--accent)" /> RAG 检索调试</div>
        <div class="sub">走与对话同一条链路（短查询扩展 → 混合检索 → 出口判定），并预测这轮交给对话会判哪个出口</div>
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
            placeholder="例如：产品（过短会自动扩展成完整检索问题）"
            @keydown.ctrl.enter="run"
          />
        </label>

        <label class="field check">
          <input v-model="form.topKOverride" type="checkbox" />
          <span class="field-label">
            覆盖 topK
            <strong v-if="form.topKOverride" class="mono" style="color: var(--accent)">{{ form.topK }}</strong>
            <em v-else class="follow mono">跟随对话{{ result ? `（${result.topK}）` : '' }}</em>
          </span>
        </label>
        <input
          v-model.number="topKView"
          class="slider"
          type="range"
          min="1"
          max="20"
          step="1"
          :disabled="!form.topKOverride"
        />

        <label class="field check">
          <input v-model="form.thresholdOverride" type="checkbox" />
          <span class="field-label">
            覆盖余弦阈值
            <strong v-if="form.thresholdOverride" class="mono" style="color: var(--accent)">{{ form.threshold.toFixed(2) }}</strong>
            <em v-else class="follow mono">跟随对话{{ result ? `（${result.similarityThreshold.toFixed(2)}）` : '' }}</em>
          </span>
        </label>
        <input
          v-model.number="thresholdView"
          class="slider"
          type="range"
          min="0"
          max="1"
          step="0.05"
          :disabled="!form.thresholdOverride"
        />

        <label class="field check">
          <input v-model="form.expand" type="checkbox" />
          <span class="field-label">短查询扩展（与对话同口径；过短的问题先补全再检索）</span>
        </label>

        <button class="btn primary run-btn" :disabled="loading" @click="run">
          <span v-if="loading" class="spinner" />
          <Icon v-else name="search" :size="14" />
          {{ loading ? '检索中…' : '执 行 检 索' }}
        </button>

        <p class="tip">
          看什么：① <strong>余弦分</strong>才是出口判据用的相似度，<strong>融合分</strong>只是相对名次
          （仅词面命中的榜首恒为 1.00，不代表相关）；② 阈值只作用于语义一路，
          关键词命中不受它约束，所以条数由 topK 决定；③ 出口预测与对话使用同一判据，
          它和"有命中"不一致时是对话拒答的真正原因。
        </p>
      </div>

      <!-- 右：结果 -->
      <div class="results">
        <div v-if="hits === null" class="empty" style="padding-top: 90px">
          <Icon name="search" :size="30" class="icon" />
          <p>输入问题并执行检索</p>
          <p class="hint">默认参数与对话完全一致，改动只影响本页，不会写日志与缓存</p>
        </div>

        <template v-else>
          <!-- 出口预测：这一卡片就是"对话会不会答"的答案 -->
          <div class="card verdict">
            <div class="verdict-head">
              <span class="verdict-label">这轮交给对话会判</span>
              <StatusTag :type="prediction.type" :text="prediction.text" />
              <span class="verdict-hint">{{ prediction.hint }}</span>
            </div>
            <div class="verdict-facts mono">
              语义过阈值 {{ result.semanticCount }} · 关键词命中 {{ result.keywordCount }} · 最终注入 {{ result.finalHits }}
              · 余弦最高分 {{ formatScore(result.semanticMaxScore) }} / 阈值 {{ result.similarityThreshold.toFixed(2) }}
              · 严格模式 {{ result.kbOnly ? '开' : '关' }}
            </div>
            <div v-if="result.expanded || result.retrievalQuery !== form.question.trim()" class="verdict-query">
              实际用于检索的问题：<code>{{ result.retrievalQuery }}</code>
              <span v-if="result.expanded" class="badge">短查询已扩展</span>
            </div>
            <div v-if="!result.executed" class="warn-line">
              向量库与关键词索引都不可用：本轮未执行检索，结果不代表库里没有内容。
            </div>
            <div v-else-if="result.degraded" class="warn-line">
              检索超时/异常降级为空：本轮的"零命中"不是结论，别据此判断知识库缺内容。
            </div>
            <div v-else-if="noSemanticEvidence" class="warn-line">
              关键词有命中但语义一路条没过阈值——对话侧仍判"无知识库依据"。
              要么问题太短/表述与文档差异大（可开短查询扩展或换完整问法），要么阈值需要按真实语料重新定标（P3-6）。
            </div>
          </div>

          <div class="results-meta mono">
            {{ hits.length }} 条命中 · 前端往返 {{ elapsed }} ms
          </div>

          <div v-if="!hits.length" class="empty" style="padding-top: 40px">
            <Icon name="alert" :size="30" class="icon" />
            <p>无命中结果</p>
            <p class="hint">知识库可能没有相关内容；也可试着关闭短查询扩展或加大 topK 再查一次</p>
          </div>

          <div v-for="(h, i) in hits" :key="i" class="card hit" :class="{ dim: h.semanticScore == null }">
            <div class="hit-head">
              <span class="rank mono">#{{ i + 1 }}</span>
              <Icon name="file" :size="13" />
              <span class="mono hit-file">{{ h.fileName }}</span>
              <span class="mono hit-chunk">chunk {{ h.chunkIndex }}</span>
              <span v-if="h.semanticScore == null" class="badge gray">仅词面命中 · 对话侧视为无据</span>
              <span class="hit-score mono" :style="{ color: semColor(h.semanticScore) }">
                余弦 {{ formatScore(h.semanticScore) }}
              </span>
            </div>
            <div class="score-track">
              <div
                class="score-bar"
                :style="{ width: `${Math.min((h.semanticScore ?? 0) * 100, 100)}%`, background: semColor(h.semanticScore) }"
              />
            </div>
            <div class="score-lines mono">
              <span>BM25 原始分：{{ formatScore(h.keywordScore) }}</span>
              <span>融合分（相对名次，仅排序用）：{{ formatScore(h.score) }}</span>
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
.field { display: flex; flex-direction: column; gap: 7px; margin-bottom: 12px; }
.field.check { flex-direction: row; align-items: flex-start; gap: 8px; margin-bottom: 6px; }
.field.check input { margin-top: 2px; }
.field-label { font-size: 12.5px; color: var(--fg-muted); }
.follow { font-style: normal; color: var(--fg-faint); font-size: 11.5px; margin-left: 6px; }
.slider { width: 100%; margin-bottom: 14px; }
.slider:disabled { opacity: 0.45; }
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
.verdict { padding: 14px 16px; margin-bottom: 12px; border-left: 3px solid var(--accent); }
.verdict-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.verdict-label { font-size: 12.5px; color: var(--fg-muted); }
.verdict-hint { font-size: 11.5px; color: var(--fg-faint); }
.verdict-facts { margin-top: 9px; font-size: 11.5px; color: var(--fg-muted); }
.verdict-query { margin-top: 7px; font-size: 11.5px; color: var(--fg-muted); }
.verdict-query code { color: var(--accent); }
.warn-line { margin-top: 9px; font-size: 11.5px; color: var(--warn); line-height: 1.7; }
.badge {
  margin-left: 8px;
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--muted);
  color: var(--accent);
}
.badge.gray { color: var(--fg-faint); }
.results-meta { font-size: 11.5px; color: var(--fg-faint); margin-bottom: 12px; }
.hit { padding: 14px 16px; margin-bottom: 12px; }
.hit.dim { opacity: 0.72; }
.hit-head { display: flex; align-items: center; gap: 8px; }
.rank { color: var(--fg-faint); font-size: 11px; }
.hit-file { font-size: 12.5px; color: var(--fg); }
.hit-chunk { font-size: 11px; color: var(--fg-faint); }
.hit-score { margin-left: auto; font-size: 12.5px; font-weight: 600; white-space: nowrap; }
.score-track {
  height: 3px;
  background: var(--muted);
  border-radius: 2px;
  margin: 9px 0 8px;
  overflow: hidden;
}
.score-bar { height: 100%; border-radius: 2px; transition: width 0.3s ease; }
.score-lines { display: flex; gap: 18px; font-size: 10.5px; color: var(--fg-faint); flex-wrap: wrap; }
.hit-snippet { margin-top: 8px; font-size: 12.5px; color: var(--fg-muted); line-height: 1.7; white-space: pre-wrap; }
.hit-doc { margin-top: 8px; font-size: 10.5px; color: var(--fg-faint); }

@media (max-width: 900px) {
  .debug-layout { flex-direction: column; }
  .panel { width: 100%; position: static; }
}
</style>
