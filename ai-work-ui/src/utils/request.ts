import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { getAccessToken, clearTokens } from '@/utils/auth'

// 后端统一返回结构
export interface R<T = unknown> {
  code: number
  msg: string
  data: T
}

const service = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
  timeout: 30000,
})

// 注入登录 token；登录接口走独立实例（见 src/api/login.ts），不经过此处
service.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 建项目/建 Key 返回的明文密钥后端只回一次、不可再查。它在屏时若有任何请求（包括此前已发出、
// 尚未落地的列表请求）返回 401/424，硬跳转会把明文连同页面一起销毁，操作者无从找回——仅推迟
// 自己主动发起的刷新挡不住在途请求的迟到 401。故以引用计数挂起跳转：明文在屏期间只清 token、
// 记下待跳转，等弹窗关闭再执行。token 已清理，挂起期间任何请求照样失败，推迟的只是「离开页面」。
let navigationHolds = 0
let navigationPending = false

function goToLogin() {
  // 用 location 跳转避免依赖 router
  if (window.location.pathname !== '/login') {
    const redirect = encodeURIComponent(window.location.pathname + window.location.search)
    window.location.href = `/login?redirect=${redirect}`
  }
}

function navigateToLogin() {
  if (navigationHolds > 0) {
    navigationPending = true
    ElMessage.error('登录态已失效，请先保存屏幕上的密钥，关闭后将返回登录页')
    return
  }
  goToLogin()
}

/** 挂起登录跳转，返回释放函数（重复调用只生效一次）。仅用于一次性明文在屏的场景。 */
export function holdLoginNavigation(): () => void {
  navigationHolds++
  let released = false
  return () => {
    if (released) return
    released = true
    navigationHolds--
    if (navigationHolds === 0 && navigationPending) {
      navigationPending = false
      goToLogin()
    }
  }
}

/** 仅供测试断言挂起状态 */
export function loginNavigationHeld(): { holds: number; pending: boolean } {
  return { holds: navigationHolds, pending: navigationPending }
}

service.interceptors.response.use(
  (response) => {
    const res = response.data as R
    if (res.code !== undefined && res.code !== 0) {
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || 'request failed'))
    }
    return response.data
  },
  (error) => {
    // 401/424：登录态失效（424 为后端 token 失效约定状态码），清 token 回登录页（带回跳地址）
    const status = error.response?.status
    if (status === 401 || status === 424) {
      clearTokens()
      navigateToLogin()
      return Promise.reject(error)
    }
    // 其他错误：后端会返回含本地化 msg 的 R 结构，优先展示后端消息
    const msg = (error.response?.data as R | undefined)?.msg || error.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

// 拦截器已将响应解包为 R 结构，这里通过 axios 第二泛型收敛类型，
// 使调用方拿到与运行时一致的 Promise<R<T>>
const request = {
  get<T = unknown>(url: string, config?: AxiosRequestConfig) {
    return service.get<T, R<T>>(url, config)
  },
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return service.post<T, R<T>>(url, data, config)
  },
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return service.put<T, R<T>>(url, data, config)
  },
  patch<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return service.patch<T, R<T>>(url, data, config)
  },
  delete<T = unknown>(url: string, config?: AxiosRequestConfig) {
    return service.delete<T, R<T>>(url, config)
  },
}

export default request
