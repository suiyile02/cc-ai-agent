import { defineStore } from 'pinia'

let seq = 0

/** 轻量全局提示（toast）：任意组件 import 后直接 useToast().push(...) */
export const useToastStore = defineStore('toast', {
  state: () => ({ items: [] }),
  actions: {
    push(message, type = 'info', duration = 3200) {
      const id = ++seq
      this.items.push({ id, message, type })
      setTimeout(() => this.dismiss(id), duration)
    },
    success(msg) { this.push(msg, 'success') },
    error(msg) { this.push(msg, 'error', 4500) },
    info(msg) { this.push(msg, 'info') },
    dismiss(id) {
      this.items = this.items.filter((t) => t.id !== id)
    },
  },
})
