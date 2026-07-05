// token 的 localStorage 存取，独立成模块以避免 request/store/router 之间的循环依赖
const ACCESS_TOKEN_KEY = 'ai-work-access-token'
const REFRESH_TOKEN_KEY = 'ai-work-refresh-token'

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function setTokens(access: string, refresh: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, access)
  localStorage.setItem(REFRESH_TOKEN_KEY, refresh)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}
