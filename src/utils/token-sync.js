import { useAuthStore } from '../stores/auth'

/**
 * 多标签页登录态同步。
 * localStorage 的 aa_token 被其它标签页改变（新登录/登出）时，
 * 本标签页整体刷新一次，重新读取最新登录态，避免出现
 * “界面是 A 账号的会话、请求却带 B 账号 token”的错位。
 */
export function watchTokenSync() {
  window.addEventListener('storage', (e) => {
    if (e.key !== 'aa_token') return
    const auth = useAuthStore()
    if ((e.newValue || '') === auth.token) return
    location.reload()
  })
}
