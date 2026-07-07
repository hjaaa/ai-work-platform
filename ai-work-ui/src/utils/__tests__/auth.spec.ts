import { beforeEach, describe, expect, it } from 'vitest'
import { getAccessToken, setTokens, clearTokens } from '../auth'

describe('token localStorage 存取', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('未设置时 getAccessToken 返回 null', () => {
    expect(getAccessToken()).toBeNull()
  })

  it('setTokens 后可读取 access token,refresh token 同时写入', () => {
    setTokens('access-1', 'refresh-1')
    expect(getAccessToken()).toBe('access-1')
    expect(localStorage.getItem('ai-work-refresh-token')).toBe('refresh-1')
  })

  it('clearTokens 同时清空 access 与 refresh', () => {
    setTokens('access-1', 'refresh-1')
    clearTokens()
    expect(getAccessToken()).toBeNull()
    expect(localStorage.getItem('ai-work-refresh-token')).toBeNull()
  })
})
