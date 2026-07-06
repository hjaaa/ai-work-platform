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
const AUTH_BASE = normalizePathPrefix(import.meta.env.VITE_AUTH_PATH || '/auth')

function normalizePathPrefix(path: string): string {
  const trimmed = path.replace(/^\/+|\/+$/g, '')
  return trimmed ? `/${trimmed}` : ''
}

function joinUrlPath(baseUrl: string, ...paths: string[]): string {
  return `${baseUrl.replace(/\/+$/g, '')}${paths.map(normalizePathPrefix).join('')}`
}

// 用独立 axios 调用绕过统一拦截器：不解包 R、不注入 Bearer、改用 Basic 客户端认证
export async function login(form: LoginForm): Promise<TokenResponse> {
  const params = new URLSearchParams({
    grant_type: 'password',
    scope: 'server',
    username: form.username,
    password: encryptPassword(form.password),
  })
  // 验证码按需携带：未展示验证码时不发送空参数
  if (form.code) {
    params.set('code', form.code)
    params.set('randomStr', form.randomStr)
  }
  const { data } = await axios.post<TokenResponse>(
    joinUrlPath(import.meta.env.VITE_API_URL, AUTH_BASE, '/oauth2/token'),
    params,
    { headers: { Authorization: BASIC_AUTH } },
  )
  return data
}

// 社交扫码登录:grant_type=mobile,mobile=TYPE@code(如 DINGTALK@{authCode});
// 后端 ValidateCodeFilter 对非 SMS 的社交登录自动跳过图形验证码
export async function socialLogin(type: string, code: string): Promise<TokenResponse> {
  const params = new URLSearchParams({
    grant_type: 'mobile',
    scope: 'server',
    mobile: `${type}@${code}`,
  })
  const { data } = await axios.post<TokenResponse>(
    joinUrlPath(import.meta.env.VITE_API_URL, AUTH_BASE, '/oauth2/token'),
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
  return `${joinUrlPath(import.meta.env.VITE_API_URL, AUTH_BASE, '/code/image')}?randomStr=${randomStr}`
}

// 查询账号是否需要图形验证码（连续失败达到阈值）；预检失败按不需要处理，由后端校验兜底。
// 用独立 axios：登录页无 token，且预检失败不应触发统一拦截器的错误提示
export async function checkCaptchaRequired(username: string): Promise<boolean> {
  try {
    const { data } = await axios.get<{ data?: boolean }>(
      joinUrlPath(import.meta.env.VITE_API_URL, AUTH_BASE, '/code/required'),
      { params: { username } },
    )
    return data.data === true
  } catch {
    return false
  }
}
