<script setup>
import { onMounted, onUnmounted } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: [Number, String], default: 560 },
})
const emit = defineEmits(['update:modelValue'])

function close() {
  emit('update:modelValue', false)
}

function onKey(e) {
  if (e.key === 'Escape' && props.modelValue) close()
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="overlay" @click.self="close">
        <div class="modal card" :style="{ width: typeof width === 'number' ? `${width}px` : width }" role="dialog" :aria-label="title">
          <div class="modal-head">
            <h3>{{ title }}</h3>
            <button class="btn ghost icon-only" aria-label="关闭" @click="close">
              <Icon name="x" :size="15" />
            </button>
          </div>
          <div class="modal-body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="modal-foot">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(17, 20, 28, 0.35);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 24px;
}
.modal {
  max-height: 82vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-pop);
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.modal-head h3 { font-size: 14.5px; font-weight: 600; }
.modal-body { padding: 18px; overflow: auto; min-height: 0; }
.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 18px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
}
.modal-enter-active, .modal-leave-active { transition: opacity 0.18s ease; }
.modal-enter-active .modal, .modal-leave-active .modal { transition: transform 0.18s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .modal, .modal-leave-to .modal { transform: translateY(10px) scale(0.98); }
</style>
