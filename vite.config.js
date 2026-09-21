import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端固定跑在 9090；开发期把 /api 代理过去，前端请求全部走同源，
// 既绕开 CORS，也让 SSE 流式不被浏览器跨域策略干扰。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
    },
  },
})
