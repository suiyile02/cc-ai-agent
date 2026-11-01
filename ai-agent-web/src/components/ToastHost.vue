<script setup>
import { useToastStore } from '../stores/toast'
import Icon from './Icon.vue'

const toast = useToastStore()

const ICON_BY_TYPE = { success: 'check', error: 'alert', info: 'info' }
</script>

<template>
  <Teleport to="body">
    <div class="toast-host" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="t in toast.items" :key="t.id" class="toast" :class="t.type" @click="toast.dismiss(t.id)">
          <span class="toast-icon"><Icon :name="ICON_BY_TYPE[t.type] || 'info'" :size="13" /></span>
          <span class="toast-msg">{{ t.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 200;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}
.toast {
  pointer-events: auto;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px 8px 8px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--fg-dim);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.05), 0 8px 24px rgba(16, 24, 40, 0.08);
  max-width: 520px;
  transition: box-shadow var(--speed), border-color var(--speed);
}
.toast:hover {
  border-color: var(--border-strong);
  box-shadow: 0 2px 4px rgba(16, 24, 40, 0.06), 0 10px 28px rgba(16, 24, 40, 0.12);
}
.toast-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  flex-shrink: 0;
}
.toast.success .toast-icon { background: var(--success-dim); color: var(--success); }
.toast.error   .toast-icon { background: var(--danger-dim);  color: var(--danger); }
.toast.info    .toast-icon { background: var(--info-dim);    color: var(--info); }
.toast-msg { line-height: 1.5; word-break: break-word; }

/* 进入用缓出弹性曲线（更自然），退出更快且轻微上浮收走；多条增减时其余顺滑让位 */
.toast-enter-active { transition: opacity 0.3s cubic-bezier(0.16, 1, 0.3, 1), transform 0.3s cubic-bezier(0.16, 1, 0.3, 1); }
.toast-leave-active { transition: opacity 0.18s ease-in, transform 0.18s ease-in; }
.toast-move { transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1); }
.toast-enter-from { opacity: 0; transform: translateY(-10px) scale(0.96); }
.toast-leave-to { opacity: 0; transform: translateY(-6px) scale(0.98); }
</style>
