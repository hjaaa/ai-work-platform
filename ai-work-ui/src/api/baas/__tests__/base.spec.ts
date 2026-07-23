import { describe, expect, it } from 'vitest'
import { resolveBaasBase, newOperationId, extractBackendMsg, isDdlLockBusy } from '../base'
import { AxiosError } from 'axios'
import type { InternalAxiosRequestConfig, AxiosResponse } from 'axios'

describe('resolveBaasBase', () => {
  it('未配置时默认 /baas', () => {
    expect(resolveBaasBase(undefined)).toBe('/baas')
    expect(resolveBaasBase('')).toBe('/baas')
  })

  it('boot 形态覆盖为 /admin', () => {
    expect(resolveBaasBase('/admin')).toBe('/admin')
  })

  it('归一化前后斜杠', () => {
    expect(resolveBaasBase('baas')).toBe('/baas')
    expect(resolveBaasBase('/baas/')).toBe('/baas')
    expect(resolveBaasBase('//admin//')).toBe('/admin')
  })
})

describe('newOperationId', () => {
  it('生成规范小写 UUID(后端 OperationIdValidator 要求)', () => {
    const id = newOperationId()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  })

  it('两次生成不相同', () => {
    expect(newOperationId()).not.toBe(newOperationId())
  })

  it('明文 HTTP 部署(非 secure context,crypto.randomUUID 为 undefined)回退 getRandomValues 仍产合法 UUID v4', () => {
    const original = crypto.randomUUID
    // 模拟 §5 明文 HTTP 部署:该 API 在非 secure context 不存在
    Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true })
    try {
      const id = newOperationId()
      // version nibble 恒为 4、variant 恒为 8/9/a/b
      expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    } finally {
      Object.defineProperty(crypto, 'randomUUID', { value: original, configurable: true })
    }
  })
})

describe('isDdlLockBusy', () => {
  function axios409(msg: string): AxiosError {
    const config = {} as InternalAxiosRequestConfig
    return new AxiosError('Request failed', '409', config, null, {
      status: 409,
      statusText: '',
      data: { code: 1, msg, data: null },
      headers: {},
      config,
    } as AxiosResponse)
  }

  it('DDL 锁忙 409 命中(可提示刷新重试)', () => {
    expect(isDdlLockBusy(axios409('该项目有 DDL 操作进行中'))).toBe(true)
  })

  it('非锁 409(指纹不一致/有损 ALTER/唯一键冲突)不命中,应直显后端 msg 不误导重试', () => {
    expect(isDdlLockBusy(axios409('同 operationId 的请求内容不一致'))).toBe(false)
  })

  it('非 AxiosError 不命中', () => {
    expect(isDdlLockBusy(new Error('x'))).toBe(false)
  })
})

describe('extractBackendMsg', () => {
  function axiosErrorWith(data: unknown): AxiosError {
    const config = {} as InternalAxiosRequestConfig
    return new AxiosError('Request failed', '400', config, null, {
      status: 400,
      statusText: '',
      data,
      headers: {},
      config,
    } as AxiosResponse)
  }

  it('从 AxiosError 响应体提取 R.msg', () => {
    const err = axiosErrorWith({ code: 1, msg: '删列为破坏性操作，须显式 allowLossy=true 确认', data: null })
    expect(extractBackendMsg(err)).toBe('删列为破坏性操作，须显式 allowLossy=true 确认')
  })

  it('响应体无 msg 时返回空串', () => {
    expect(extractBackendMsg(axiosErrorWith({}))).toBe('')
    expect(extractBackendMsg(axiosErrorWith(undefined))).toBe('')
  })

  it('普通 Error 取 message,其他值返回空串', () => {
    expect(extractBackendMsg(new Error('boom'))).toBe('boom')
    expect(extractBackendMsg('x')).toBe('')
    expect(extractBackendMsg(null)).toBe('')
  })
})
