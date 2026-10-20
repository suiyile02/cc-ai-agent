<script setup>
import { useToastStore } from '../stores/toast'

const toast = useToastStore()
</script>

<template>
  <Teleport to="body">
    <div class="toast-host" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="t in toast.items" :key="t.id" class="toast card" :class="t.type" @click="toast.dismiss(t.id)">
          <span class="toast-msg">{{ t.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 18px;
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
  padding: 9px 18px;
  font-size: 13px;
  border-left: 3px solid var(--info);
  color: var(--fg-dim);
  box-shadow: 0 8px 28px rgba(16, 24, 40, 0.14);
  max-width: 520px;
}
.toast.success { border-left-color: var(--accent); }
.toast.error { border-left-color: var(--danger); }
.toast.info { border-left-color: var(--info); }
.toast-enter-active, .toast-leave-active { transition: all 0.22s ease; }
.toast-enter-from { opacity: 0; transform: translateY(-8px); }
.toast-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
