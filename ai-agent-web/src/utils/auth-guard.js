import router from '../router'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'

/**
 * 后端不可达时的强制退出（用户选择的安全策略）：
 * 清空登录态与会话缓存，回登录页并带上 ?offline=1 提示。
 * 已登出时静默返回，避免并发请求重复触发。
 */
export function forceOfflineLogout() {
  const auth = useAuthStore()
  if (!auth.isLoggedIn) return
  auth.logout()
  useChatStore().$reset()
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login', query: { offline: '1' } })
  }
}
