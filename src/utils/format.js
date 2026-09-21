/** 展示格式化小工具 */

export function formatDateTime(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function formatTime(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

export function formatFileSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

export function formatDuration(ms) {
  if (ms == null) return '-'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms} ms`
}

export function formatScore(score) {
  if (score == null) return '-'
  return Number(score).toFixed(4)
}

/** 截断长文本（表格单元格用） */
export function truncate(text, n = 60) {
  if (!text) return '-'
  const s = String(text)
  return s.length > n ? `${s.slice(0, n)}…` : s
}

/** JSON 美化；失败则原样返回 */
export function prettyJson(text) {
  if (text == null || text === '') return '（空）'
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return String(text)
  }
}
