import { defineStore } from 'pinia'

const TOKEN_KEY = 'aa_token'
const USER_KEY = 'aa_user'

/**
 * 登录态：token + 用户信息，持久化到 localStorage。
 * 注册/登录成功即写入；登出清空并回登录页。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN',
    displayName: (s) => s.user?.nickname || s.user?.username || '未登录',
  },
  actions: {
    setAuth(token, user) {
      this.token = token
      this.user = user
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    logout(clearStorage = true) {
      this.token = ''
      this.user = null
      if (clearStorage) {
        localStorage.removeItem(TOKEN_KEY)
        localStorage.removeItem(USER_KEY)
      }
    },
  },
})
