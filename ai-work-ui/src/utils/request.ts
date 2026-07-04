import axios from 'axios'
import { ElMessage } from 'element-plus'

// 后端统一返回结构
export interface R<T = unknown> {
  code: number
  msg: string
  data: T
}

const request = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
  timeout: 30000,
})

// 预留请求拦截：登录联调后在此注入 Authorization 头
request.interceptors.request.use((config) => config)

request.interceptors.response.use(
  (response) => {
    const res = response.data as R
    if (res.code !== undefined && res.code !== 0) {
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || 'request failed'))
    }
    return response.data
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
