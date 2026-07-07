import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import request from '../request'
import type { R } from '../request'
import { setTokens, getAccessToken } from '../auth'

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn() },
}))

// 通过 axios 的 per-request adapter 伪造响应,不发真实网络请求
function okAdapter<T>(payload: R<T>) {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => ({
    data: payload,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  })
}

function httpErrorAdapter(status: number, payload?: unknown) {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    throw new AxiosError('Request failed', String(status), config, null, {
      status,
      statusText: '',
      data: payload,
      headers: {},
      config,
    })
  }
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
})

describe('请求拦截器', () => {
  it('已登录时注入 Bearer token', async () => {
    setTokens('token-1', 'refresh-1')
    let captured: InternalAxiosRequestConfig | undefined
    await request.get('/demo', {
      adapter: async (config) => {
        captured = config
        return okAdapter({ code: 0, msg: '', data: null })(config)
      },
    })
    expect(captured?.headers.Authorization).toBe('Bearer token-1')
  })

  it('未登录时不注入 Authorization', async () => {
    let captured: InternalAxiosRequestConfig | undefined
    await request.get('/demo', {
      adapter: async (config) => {
        captured = config
        return okAdapter({ code: 0, msg: '', data: null })(config)
      },
    })
    expect(captured?.headers.Authorization).toBeUndefined()
  })
})

describe('响应拦截器', () => {
  it('code=0 时解包返回后端 R 结构', async () => {
    const res = await request.get<string>('/demo', {
      adapter: okAdapter<string>({ code: 0, msg: 'ok', data: 'hello' }),
    })
    expect(res.code).toBe(0)
    expect(res.data).toBe('hello')
  })

  it('业务码非 0 时提示后端消息并拒绝', async () => {
    await expect(
      request.get('/demo', { adapter: okAdapter({ code: 1, msg: '用户名重复', data: null }) }),
    ).rejects.toThrow('用户名重复')
    expect(ElMessage.error).toHaveBeenCalledWith('用户名重复')
  })

  // 401/424 用例只校验登录态清理与拒绝行为:跳转依赖 window.location.href 赋值,
  // jsdom 的 location 不可替换,无法断言跳转地址,且会打印一行无害的
  // "Not implemented: navigation" 提示
  it('401 时清除本地 token 并拒绝,不弹错误提示', async () => {
    setTokens('token-1', 'refresh-1')
    await expect(request.get('/demo', { adapter: httpErrorAdapter(401) })).rejects.toThrow()
    expect(getAccessToken()).toBeNull()
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('424(后端 token 失效约定码)与 401 同样清除登录态', async () => {
    setTokens('token-1', 'refresh-1')
    await expect(request.get('/demo', { adapter: httpErrorAdapter(424) })).rejects.toThrow()
    expect(getAccessToken()).toBeNull()
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('其他 HTTP 错误优先展示后端返回的 msg', async () => {
    await expect(
      request.get('/demo', {
        adapter: httpErrorAdapter(500, { code: 1, msg: '服务器开小差了', data: null }),
      }),
    ).rejects.toThrow()
    expect(ElMessage.error).toHaveBeenCalledWith('服务器开小差了')
  })

  it('后端未返回 msg 时回退到 error.message', async () => {
    await expect(request.get('/demo', { adapter: httpErrorAdapter(502) })).rejects.toThrow()
    expect(ElMessage.error).toHaveBeenCalledWith('Request failed')
  })
})
