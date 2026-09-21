<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { useToastStore } from '../stores/toast'
import Icon from '../components/Icon.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()
const toast = useToastStore()

const mode = ref('login') // login | register
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '' })
const showExpired = ref(route.query.expired === '1')
const showOffline = ref(route.query.offline === '1')

function switchMode(m) {
  mode.value = m
  form.password = ''
}

function afterAuth(body) {
  // 换账号登录前清空上一个用户的会话缓存，防止跨账号残留
  chat.$reset()
  auth.setAuth(body.data.token, body.data.user)
  toast.success(mode.value === 'login' ? '登录成功' : '注册成功')
  router.push(typeof route.query.redirect === 'string' ? route.query.redirect : '/chat')
}

async function submit() {
  showOffline.value = false
  if (!form.username.trim() || !form.password) {
    toast.error('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'login') {
      afterAuth(await authApi.login({ username: form.username.trim(), password: form.password }))
    } else {
      if (!/^[a-zA-Z0-9_]{3,32}$/.test(form.username.trim())) {
        toast.error('用户名需为 3~32 位字母/数字/下划线')
        return
      }
      if (form.password.length < 6 || form.password.length > 64) {
        toast.error('密码长度需在 6~64 之间')
        return
      }
      afterAuth(await authApi.register({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim() || undefined,
      }))
    }
  } catch (e) {
    if (e.offline) showOffline.value = true
    else toast.error(e.message)
  } finally {
    loading.value = false
  }
}

function fillAdmin() {
  mode.value = 'login'
  form.username = 'admin'
  form.password = 'admin123'
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="login-brand">
        <span class="brand-mark"><Icon name="terminal" :size="20" /></span>
        <h1>AI Agent 控制台</h1>
        <p>智能问答 · RAG 检索 · Agent 工具</p>
      </div>

      <div v-if="showExpired" class="expired-tip">
        <Icon name="alert" :size="14" />
        登录已过期，请重新登录
      </div>
      <div v-if="showOffline" class="expired-tip">
        <Icon name="alert" :size="14" />
        无法连接服务器，请确认后端（9090 端口）已启动
      </div>

      <div class="mode-tabs" role="tablist">
        <button
          class="mode-tab"
          :class="{ active: mode === 'login' }"
          role="tab"
          :aria-selected="mode === 'login'"
          @click="switchMode('login')"
        >登录</button>
        <button
          class="mode-tab"
          :class="{ active: mode === 'register' }"
          role="tab"
          :aria-selected="mode === 'register'"
          @click="switchMode('register')"
        >注册</button>
      </div>

      <form class="login-form" @submit.prevent="submit">
        <label class="field">
          <span class="field-label">用户名</span>
          <input
            v-model="form.username"
            class="input"
            placeholder="3~32 位字母/数字/下划线"
            autocomplete="username"
          />
        </label>
        <label class="field">
          <span class="field-label">密码</span>
          <input
            v-model="form.password"
            class="input"
            type="password"
            :placeholder="mode === 'register' ? '6~64 位' : '请输入密码'"
            autocomplete="current-password"
          />
        </label>
        <label v-if="mode === 'register'" class="field">
          <span class="field-label">昵称（可选）</span>
          <input v-model="form.nickname" class="input" placeholder="不填则使用用户名" />
        </label>

        <button class="btn primary submit" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner" />
          {{ mode === 'login' ? '登 录' : '注 册' }}
        </button>
      </form>

      <div class="login-hint">
        <span>首次联调可用种子账号</span>
        <button class="link-btn mono" type="button" @click="fillAdmin">admin / admin123</button>
        <span>（点击自动填充）</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--bg-deep);
}

.login-card {
  width: 400px;
  max-width: 100%;
  padding: 34px 34px 26px;
}

.login-brand { text-align: center; margin-bottom: 24px; }
.brand-mark {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--accent-dim);
  color: var(--accent);
  border: 1px solid var(--accent-border);
  margin-bottom: 14px;
}
.login-brand h1 { font-size: 19px; font-weight: 650; letter-spacing: 0.4px; }
.login-brand p { color: var(--fg-faint); font-size: 12px; margin-top: 5px; letter-spacing: 1px; }

.expired-tip {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--warn-dim);
  color: var(--warn);
  font-size: 12.5px;
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  margin-bottom: 14px;
}

.mode-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 3px;
  margin-bottom: 20px;
}
.mode-tab {
  height: 30px;
  border: none;
  background: transparent;
  color: var(--fg-muted);
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
  transition: all var(--speed);
}
.mode-tab.active { background: var(--muted); color: var(--fg); font-weight: 550; }

.login-form { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12.5px; color: var(--fg-muted); }
.field .input { height: 38px; }
.submit { height: 38px; margin-top: 4px; font-size: 14px; }

.login-hint {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  text-align: center;
  font-size: 12px;
  color: var(--fg-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  flex-wrap: wrap;
}
.link-btn {
  background: none;
  border: none;
  color: var(--accent);
  cursor: pointer;
  font-size: 12px;
  padding: 0;
  text-decoration: underline dotted;
}
</style>
