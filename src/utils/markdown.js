/**
 * 轻量安全 Markdown 渲染（无外部依赖）。
 * 先整体 HTML 转义防注入，再恢复受控的格式标签。
 * 支持：代码块/行内代码/标题/粗斜体/链接/无序有序列表/引用/分隔线。
 */

const escapeHtml = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')

function renderInline(text) {
  let s = text
  // 行内代码 `code`
  s = s.replace(/`([^`]+)`/g, '<code class="md-code">$1</code>')
  // 链接 [text](url) —— url 仅允许 http/https
  s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  // 粗体 / 斜体
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  return s
}

export function renderMarkdown(src) {
  if (!src) return ''
  const escaped = escapeHtml(String(src))
  const lines = escaped.split('\n')
  const out = []
  let inCode = false
  let codeBuf = []
  let listType = null // 'ul' | 'ol'
  let paraBuf = []

  const closeList = () => {
    if (listType) { out.push(`</${listType}>`); listType = null }
  }
  const flushPara = () => {
    if (paraBuf.length) {
      out.push(`<p>${paraBuf.map(renderInline).join('<br>')}</p>`)
      paraBuf = []
    }
  }

  for (const line of lines) {
    const fence = line.match(/^```(\w*)/)
    if (fence) {
      if (inCode) {
        out.push(`<pre class="md-pre"><code>${codeBuf.join('\n')}</code></pre>`)
        codeBuf = []
        inCode = false
      } else {
        flushPara(); closeList()
        inCode = true
      }
      continue
    }
    if (inCode) { codeBuf.push(line); continue }

    const t = line.trim()
    if (t === '') { flushPara(); closeList(); continue }

    const heading = t.match(/^(#{1,6})\s*(.*)/)
    // 标题：兼容 # 后紧跟 emoji/中文（无空格）的写法；无标题文本则忽略
    if (heading && heading[2]) {
      flushPara(); closeList()
      const level = Math.min(heading[1].length, 6)
      out.push(`<h${level} class="md-h">${renderInline(heading[2])}</h${level}>`)
      continue
    }
    if (/^(-{3,}|\*{3,})$/.test(t)) {
      flushPara(); closeList()
      out.push('<hr class="md-hr">')
      continue
    }
    const ul = t.match(/^[-*+]\s+(.*)/)
    const ol = t.match(/^(\d+)[.、]\s+(.*)/)
    if (ul || ol) {
      flushPara()
      const want = ul ? 'ul' : 'ol'
      if (listType !== want) { closeList(); out.push(`<${want} class="md-list">`); listType = want }
      out.push(`<li>${renderInline(ul ? ul[1] : ol[2])}</li>`)
      continue
    }
    const quote = t.match(/^&gt;\s?(.*)/)
    if (quote) {
      flushPara(); closeList()
      out.push(`<blockquote class="md-quote">${renderInline(quote[1])}</blockquote>`)
      continue
    }
    paraBuf.push(line)
  }
  // 收尾
  if (inCode) out.push(`<pre class="md-pre"><code>${codeBuf.join('\n')}</code></pre>`)
  flushPara(); closeList()
  return out.join('\n')
}
