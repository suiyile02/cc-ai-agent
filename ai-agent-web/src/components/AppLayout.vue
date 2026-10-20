<script setup>
import { computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sessionApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useToastStore } from '../stores/toast'
import Icon from './Icon.vue'
import SessionList from './SessionList.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()
const toast = useToastStore()

// 权限导航：普通用户仅「智能对话」，管理员可见全部
const NAV = [
  { name: 'chat', label: '智能对话', icon: 'chat' },
  { name: 'knowledge', label: '知识库', icon: 'database', adminOnly: true },
  { name: 'debug', label: '检索调试', icon: 'sliders', adminOnly: true },
  { name: 'system', label: '系统日志', icon: 'logs', adminOnly: true },
]
const navItems = computed(() => NAV.filter((n) => !n.adminOnly || auth.isAdmin))

/* ---------------- 新建会话 ---------------- */
const creating = ref(false)

async function createSession() {
  creating.value = true
  try {
    // 不传 sessionType，后端默认 HYBRID（自动兼顾知识库检索与工具调用）
    const body = await sessionApi.create({ title: '' })
    chat.upsertSession(body.data)
    chat.select(body.data.sessionId)
    chat.ensureMessages(body.data.sessionId).loaded = true // 新会话无历史，免拉取
    keyword.value = '' // 清空搜索，确保新会话可见
    if (route.name !== 'chat') router.push({ name: 'chat' })
    toast.success('会话已创建')
  } catch (e) {
    toast.error(e.message)
  } finally {
    creating.value = false
  }
}

/* ---------------- 会话查询 ---------------- */
const searchOpen = ref(false)
const keyword = ref('')
const searchInput = ref(null)

function toggleSearch() {
  searchOpen.value = !searchOpen.value
  if (searchOpen.value) {
    nextTick(() => searchInput.value?.focus())
  } else {
    keyword.value = ''
  }
}

function logout() {
  auth.logout()
  chat.$reset()
  router.push({ name: 'login' })
}

const avatarChar = computed(() => auth.displayName.slice(0, 1).toUpperCase())
</script>

<template>
  <div class="shell">
    <!-- 最左侧主侧栏：新对话 / 会话查询 / 页面导航 / 会话记录(按日期分组) / 用户 -->
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark"><Icon name="terminal" :size="15" /></span>
        <span class="brand-name">AI Agent 控制台</span>
      </div>

      <div class="new-row">
        <button class="new-chat-btn" :disabled="creating" @click="createSession">
          <span v-if="creating" class="spinner" />
          <Icon v-else name="plus" :size="15" />
          <span class="new-chat-label">开始新对话</span>
        </button>
        <button
          class="icon-btn"
          :class="{ active: searchOpen }"
          title="查询会话"
          aria-label="查询会话"
          @click="toggleSearch"
        >
          <Icon name="search" :size="15" />
        </button>
      </div>

      <div v-if="searchOpen" class="search-wrap">
        <Icon name="search" :size="13" class="search-ico" />
        <input
          ref="searchInput"
          v-model="keyword"
          class="search-input"
          placeholder="搜索标题 / sessionId"
          @keydown.esc="toggleSearch"
        />
        <button v-if="keyword" class="search-clear" aria-label="清空搜索" @click="keyword = ''">
          <Icon name="x" :size="12" />
        </button>
      </div>

      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ active: route.name === item.name }"
        >
          <Icon :name="item.icon" :size="16" />
          <span class="nav-label">{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sessions-head">会话记录</div>
      <SessionList :keyword="keyword" />

      <div class="sidebar-foot">
        <div class="user-card">
          <span class="avatar" aria-hidden="true">{{ avatarChar }}</span>
          <div class="user-meta">
            <span class="user-name" :title="auth.displayName">{{ auth.displayName }}</span>
            <span class="user-role">{{ auth.user?.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
          </div>
          <button class="logout-btn" title="退出登录" aria-label="退出登录" @click="logout">
            <Icon name="logout" :size="15" />
          </button>
        </div>
      </div>
    </aside>

    <!-- 内容区 -->
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 264px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-deep);
  border-right: 1px solid var(--border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 16px 14px 12px;
}
.brand-mark {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  flex-shrink: 0;
}
.brand-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--fg);
}

/* 新对话 + 查询按钮 */
.new-row {
  display: flex;
  gap: 6px;
  padding: 0 10px;
  margin-bottom: 6px;
}
.new-chat-btn {
  flex: 1;
  height: 38px;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--fg);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: all var(--speed);
}
.new-chat-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent); }
.new-chat-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.icon-btn {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--fg-dim);
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: all var(--speed);
}
.icon-btn:hover { color: var(--accent); border-color: var(--accent); }
.icon-btn.active { color: var(--accent); border-color: var(--accent); background: var(--accent-dim); }

/* 会话查询展开框 */
.search-wrap { position: relative; margin: 0 10px 8px; }
.search-ico {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--fg-faint);
  pointer-events: none;
}
.search-input {
  width: 100%;
  height: 34px;
  border-radius: 10px;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--fg);
  font-family: inherit;
  font-size: 12.5px;
  padding: 0 28px 0 30px;
  outline: none;
  transition: border-color var(--speed), box-shadow var(--speed);
}
.search-input::placeholder { color: var(--fg-faint); }
.search-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--ring); }
.search-clear {
  position: absolute;
  right: 7px;
  top: 50%;
  transform: translateY(-50%);
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: var(--fg-faint);
  cursor: pointer;
}
.search-clear:hover { color: var(--fg); background: var(--surface-2); }

.nav {
  padding: 0 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  color: var(--fg-dim);
  font-size: 13.5px;
  transition: all var(--speed);
  flex-shrink: 0;
}
.nav-item:hover { color: var(--fg); background: var(--surface-2); }
.nav-item.active {
  color: var(--fg);
  background: var(--surface);
  font-weight: 550;
  box-shadow: var(--shadow-card);
}

.sessions-head {
  padding: 14px 12px 4px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.5px;
  color: var(--fg-faint);
  flex-shrink: 0;
}

.sidebar-foot { padding: 10px; border-top: 1px solid var(--border); flex-shrink: 0; }
.user-card {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 6px;
  border-radius: var(--radius-sm);
}
.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.user-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.user-name {
  font-size: 12.5px;
  color: var(--fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-role { font-size: 11px; color: var(--fg-muted); }
.logout-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 6px;
  color: var(--fg-muted);
  cursor: pointer;
  transition: all var(--speed);
  flex-shrink: 0;
}
.logout-btn:hover { background: var(--surface-2); color: var(--danger); }

.content { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.content > :deep(*) { flex: 1; min-height: 0; }

/* 窄屏：收成图标栏（会话列表隐藏） */
@media (max-width: 900px) {
  .sidebar { width: 64px; }
  .brand { justify-content: center; padding: 14px 0 8px; }
  .brand-name, .nav-label, .new-chat-label, .sessions-head { display: none; }
  .search-wrap { display: none; }
  .icon-btn { display: none; }
  .new-row { padding: 0 8px; }
  .new-chat-btn { flex: none; width: 44px; height: 38px; padding: 0; }
  .nav { padding: 0 8px; }
  .nav-item { justify-content: center; padding: 0; }
  .user-card { justify-content: center; padding: 8px 0; }
  .user-meta { display: none; }
}
</style>
