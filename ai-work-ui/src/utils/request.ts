import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

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

// 预留请求拦截：登录联调后在此注入 Authorization 头
service.interceptors.request.use((config) => config)

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
    // 401/424 等场景后端会返回含本地化 msg 的 R 结构，优先展示后端消息
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
  delete<T = unknown>(url: string, config?: AxiosRequestConfig) {
    return service.delete<T, R<T>>(url, config)
  },
}

export default request
