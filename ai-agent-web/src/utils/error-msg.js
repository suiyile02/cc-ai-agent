/**
 * 用户可见的网络/鉴权错误统一文案（唯一事实来源）。
 * api/index.js、utils/sse.js、views/LoginView.vue 共用；
 * 文案中禁止出现后端地址、端口、接口路径等内部实现细节。
 */
export const MSG_NET_UNREACHABLE = '无法连接服务器，请稍后重试'
export const MSG_AUTH_EXPIRED = '登录状态已失效，请重新登录'
export const MSG_REQUEST_FAILED = '请求失败，请稍后重试'
