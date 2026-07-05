import axios from 'axios'
import request from '@/utils/request'
import { encryptPassword } from '@/utils/crypto'

export interface LoginForm {
  username: string
  password: string
  code: string
  randomStr: string
}

// /auth/oauth2/token 返回标准 OAuth2 结构（非 R 包装）
export interface TokenResponse {
  access_token: string
  refresh_token: string
  token_type: string
  expires_in: number
  user_id: string
  username: string
}

const BASIC_AUTH =
  'Basic ' + btoa(`${import.meta.env.VITE_CLIENT_ID}:${import.meta.env.VITE_CLIENT_SECRET}`)

// 认证服务路径前缀：微服务形态经网关为 /auth；单体(boot)形态 context-path 为 /admin，
// 部署 boot 形态时通过 VITE_AUTH_PATH=/admin 覆盖
const AUTH_BASE = import.meta.env.VITE_AUTH_PATH || '/auth'

// 用独立 axios 调用绕过统一拦截器：不解包 R、不注入 Bearer、改用 Basic 客户端认证
export async function login(form: LoginForm): Promise<TokenResponse> {
  const params = new URLSearchParams({
    grant_type: 'password',
    scope: 'server',
    username: form.username,
    password: encryptPassword(form.password),
    code: form.code,
    randomStr: form.randomStr,
  })
  const { data } = await axios.post<TokenResponse>(
    `${import.meta.env.VITE_API_URL}${AUTH_BASE}/oauth2/token`,
    params,
    { headers: { Authorization: BASIC_AUTH } },
  )
  return data
}

export function logout() {
  return request.delete<boolean>(`${AUTH_BASE}/token/logout`)
}

// 图形验证码图片地址，randomStr 为前端随机串，登录时需原样带回
export function imageCodeUrl(randomStr: string): string {
  return `${import.meta.env.VITE_API_URL}${AUTH_BASE}/code/image?randomStr=${randomStr}`
}
