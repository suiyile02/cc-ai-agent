<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  page: { type: Number, required: true },
  size: { type: Number, required: true },
  total: { type: Number, required: true },
  totalPages: { type: Number, required: true },
})
const emit = defineEmits(['change'])

const pages = computed(() => {
  const t = props.totalPages
  const c = props.page
  if (t <= 7) return Array.from({ length: t }, (_, i) => i + 1)
  const set = new Set([1, 2, c - 1, c, c + 1, t - 1, t])
  const list = [...set].filter((p) => p >= 1 && p <= t).sort((a, b) => a - b)
  const out = []
  let prev = 0
  for (const p of list) {
    if (p - prev > 1) out.push('…')
    out.push(p)
    prev = p
  }
  return out
})

function go(p) {
  if (p === '…' || p < 1 || p > props.totalPages || p === props.page) return
  emit('change', p)
}
</script>

<template>
  <div v-if="total > 0" class="pager">
    <span class="pager-total">共 {{ total }} 条</span>
    <button class="pager-btn" :disabled="page <= 1" aria-label="上一页" @click="go(page - 1)">
      <Icon name="chevron-right" :size="13" style="transform: rotate(180deg)" />
    </button>
    <template v-for="(p, i) in pages" :key="`${p}-${i}`">
      <span v-if="p === '…'" class="pager-ellipsis">…</span>
      <button v-else class="pager-btn" :class="{ active: p === page }" @click="go(p)">
        {{ p }}
      </button>
    </template>
    <button class="pager-btn" :disabled="page >= totalPages" aria-label="下一页" @click="go(page + 1)">
      <Icon name="chevron-right" :size="13" />
    </button>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 2px 2px;
}
.pager-total { font-size: 12px; color: var(--fg-faint); margin-right: 6px; }
.pager-btn {
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  color: var(--fg-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: all var(--speed);
}
.pager-btn:hover:not(:disabled):not(.active) { background: var(--surface-2); color: var(--fg); }
.pager-btn.active { background: var(--accent-dim); color: var(--accent); border-color: var(--accent-border); }
.pager-btn:disabled { opacity: 0.35; cursor: not-allowed; }
.pager-ellipsis { color: var(--fg-faint); padding: 0 2px; }
</style>
