# AI Agent 控制台（前端）

对接后端 [ai-agent](../ai-agent/README.md)（Spring Boot 4 + Spring AI，端口 9090）的 Vue 3 前端。
**技术栈：Vue 3（Composition API）+ JavaScript + Vite + Pinia + Vue Router + Axios**，无 UI 组件库（DeepSeek 网页版式浅色简洁风：浅灰侧栏 + 白色内容区 + 蓝色强调 `#4D6BFE`，设计令牌全部集中在 `src/styles/main.css` 的 `:root`，换主题只改这一个文件）。普通用户仅可用「智能对话」，知识库/检索调试/系统日志仅管理员可见（导航过滤 + 路由守卫双重拦截）。

## 快速开始

```bash
# 1) 启动后端（见 ai-agent/README.md：docker compose up -d → mvn spring-boot:run，端口 9090）

# 2) 启动前端
npm install
npm run dev
# 打开 http://localhost:5173

# 3) 登录：种子管理员 admin / admin123（或页面右上角注册新账号）
```

开发期 Vite 已把 `/api` 代理到 `http://localhost:9090`（见 `vite.config.js`），前端请求全部同源，无需关心 CORS 与 Cookie。

## 功能页面 ↔ 后端接口对照

| 页面 | 路由 | 覆盖的后端接口 |
|---|---|---|
| 登录/注册 | `/login` | `POST /api/auth/login` · `POST /api/auth/register` · `GET /api/auth/me` |
| 智能对话 | `/chat` | `POST /api/sessions`（新建，支持 RAG/AGENT/HYBRID）· `GET /api/sessions` · `GET /api/sessions/{id}/messages`（历史回显，2026-09 新增）· `PUT /api/sessions/{id}/archive` · `DELETE /api/sessions/{id}` · `POST /api/ai/chat`（同步）· `POST /api/ai/chat/stream`（SSE 流式，逐字输出 + 停止按钮 + 引用来源展示） |
| 知识库 | `/knowledge` | `POST /api/knowledge/upload`（单文件，带进度）· `POST /api/knowledge/upload/batch`（批量，2026-09 新增：多选文件逐文件成败，弹窗展示结果）· `GET /api/knowledge/documents`（文件名/状态/时间筛选）· `DELETE /api/knowledge/documents/{id}` · `POST /api/knowledge/documents/{id}/reprocess`；有“待处理/处理中”文档时每 5s 自动轮询状态 |
| 检索调试 | `/debug` | `POST /api/ai/rag/search`（topK / 相似度阈值滑杆即时调参，命中按分数条展示） |
| 系统日志 | `/system` | `GET /api/system/chat-logs` · `tool-call-logs` · `rag-decisions` · `context-logs`（四个页签；管理员可按 userId 过滤，普通用户后端强制只看本人） |

## 目录结构

```
src/
├── api/index.js          axios 实例：自动带 Bearer Token；统一解 {code,message,data}；
│                         401/6005 自动踢回登录页；所有接口按模块分组导出
├── stores/
│   ├── auth.js           登录态（token+user，localStorage 持久化）
│   ├── chat.js           会话列表 + 每会话消息记录（内存）
│   └── toast.js          全局提示
├── router/index.js       路由 + 登录守卫（无 token 一律去 /login）
├── utils/
│   ├── sse.js            流式对话：fetch + ReadableStream 手工解析 SSE（EventSource 不支持 POST）
│   ├── markdown.js       轻量安全 Markdown 渲染（先整体转义再恢复标签，防注入）
│   └── format.js         时间/文件大小/耗时/JSON 美化
├── components/           AppLayout（侧边导航）、Icon（内置 SVG 图标集）、Modal、
│                         Pagination、StatusTag、ToastHost
└── views/                LoginView · ChatView · KnowledgeView · DebugView · SystemView
```

## 联调要点

1. **登录态**：登录/注册成功即把 JWT 存 localStorage；之后所有请求自动带 `Authorization: Bearer <token>`。收到 `code=6005` 或 HTTP 401 时自动清登录态（含聊天缓存）并跳登录页（URL 带 `?expired=1` 提示）。多标签页登录态不一致时自动刷新同步。
2. **会话隔离**：后端列表按登录用户的 userId 强过滤；对话页头部展示”归属 uid”徽标与可点击复制的 sessionId，用于核对身份。换账号登录时前端会话缓存自动清空。
3. **历史回显**：选中会话时自动调 `GET /api/sessions/{id}/messages` 拉取服务端记忆中的 USER/ASSISTANT 消息；因滚动摘要机制，记忆表只保留最近若干条，更早内容以”较早对话已折叠为摘要”折叠条展示。**该接口为 2026-09 新增，需重启后端生效**，未重启时会提示”历史消息加载失败”但不影响新对话。
4. **统一响应**：拦截器把后端 `{code,message,data,timestamp}` 解包后整体返回，`body.data` 才是业务数据，`body.message` 可直接弹 toast；`code !== 200` 会 throw（message 就是后端错误文案）。
5. **流式对话**：`POST /api/ai/chat/stream` 的 SSE 用 fetch 流解析（`data:` 帧 → 增量文本，`data:[DONE]` 结束），支持 AbortController 手动停止。
6. **状态字典**：知识库文档 0=待处理 1=处理中 2=已完成 3=失败；会话 status 1=进行中 0=已归档；工具日志 status=SUCCESS/FAILED——前端展示与后端字段一一对应，联调时看页面标签即可核对。

## 构建产物

```bash
npm run build   # 输出 dist/，可交给 Nginx 等静态托管（部署时把 /api 反代到 9090）
```
