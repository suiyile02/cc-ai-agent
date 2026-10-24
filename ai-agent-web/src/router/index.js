import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('../components/AppLayout.vue'),
    children: [
      { path: '', redirect: '/chat' },
      {
        path: 'chat',
        name: 'chat',
        component: () => import('../views/ChatView.vue'),
        meta: { title: '智能对话' },
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('../views/KnowledgeView.vue'),
        // 所有登录用户可进(列表+查看全文); 管理操作在页面内按 isAdmin 隐藏, 后端另有 @RequireAdmin 兜底
        meta: { title: '知识库' },
      },
      {
        path: 'debug',
        name: 'debug',
        component: () => import('../views/DebugView.vue'),
        meta: { title: '检索调试', adminOnly: true },
      },
      {
        path: 'system',
        name: 'system',
        component: () => import('../views/SystemView.vue'),
        // 管理员看全部日志; 演示账号只看对话日志(页面内按角色裁剪), 普通用户不可进
        meta: { title: '系统日志', adminOrDemo: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: to.fullPath !== '/chat' ? { redirect: to.fullPath } : {} }
  }
  // 管理员专属页面：普通用户访问一律回对话页
  if (to.meta.adminOnly && !auth.isAdmin) {
    return { name: 'chat' }
  }
  // 管理员或演示账号可进的页面(如系统日志)：普通用户回对话页
  if (to.meta.adminOrDemo && !auth.isAdmin && !auth.isDemo) {
    return { name: 'chat' }
  }
  if (to.name === 'login' && auth.isLoggedIn) return { path: '/chat' }
  return true
})

export default router
