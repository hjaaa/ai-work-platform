import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DTLoginSuccess } from '../dingtalk'

// dingtalk.ts 持有模块级状态(epoch、sdkPromise),每个用例重新加载模块隔离
async function importFresh() {
  vi.resetModules()
  return await import('../dingtalk')
}

const FRAME = { id: 'qr-container' }
const PARAMS = {
  redirect_uri: 'https://example.com/login',
  client_id: 'client-id',
  scope: 'openid',
  response_type: 'code',
}

beforeEach(() => {
  delete window.DTFrameLogin
  document.head.querySelectorAll('script').forEach((s) => s.remove())
})

describe('dtFrameLogin 代次去重', () => {
  it('SDK 未加载时直接抛错', async () => {
    const { dtFrameLogin } = await importFresh()
    expect(() => dtFrameLogin(FRAME, PARAMS, vi.fn(), vi.fn())).toThrow('钉钉扫码组件加载失败')
  })

  it('浮层重复挂载时,仅最新一次调用的成功回调生效', async () => {
    const { dtFrameLogin } = await importFresh()
    const sdkCalls: Array<{
      onSuccess: (result: DTLoginSuccess) => void
      onError: (errorMsg: string) => void
    }> = []
    window.DTFrameLogin = (_frame, _params, onSuccess, onError) => {
      sdkCalls.push({ onSuccess, onError })
    }

    const first = { onSuccess: vi.fn(), onError: vi.fn() }
    const second = { onSuccess: vi.fn(), onError: vi.fn() }
    dtFrameLogin(FRAME, PARAMS, first.onSuccess, first.onError)
    dtFrameLogin(FRAME, PARAMS, second.onSuccess, second.onError)

    // 模拟 SDK 旧实例抢先消费一次性 authCode 的场景:旧代次回调必须被丢弃
    sdkCalls[0]!.onSuccess({ redirectUrl: 'url', authCode: 'stale-code' })
    sdkCalls[1]!.onSuccess({ redirectUrl: 'url', authCode: 'fresh-code' })

    expect(first.onSuccess).not.toHaveBeenCalled()
    expect(second.onSuccess).toHaveBeenCalledOnce()
    expect(second.onSuccess).toHaveBeenCalledWith(
      expect.objectContaining({ authCode: 'fresh-code' }),
    )
  })

  it('错误回调同样按代次过滤', async () => {
    const { dtFrameLogin } = await importFresh()
    const sdkCalls: Array<{ onError: (errorMsg: string) => void }> = []
    window.DTFrameLogin = (_frame, _params, _onSuccess, onError) => {
      sdkCalls.push({ onError })
    }

    const first = { onSuccess: vi.fn(), onError: vi.fn() }
    const second = { onSuccess: vi.fn(), onError: vi.fn() }
    dtFrameLogin(FRAME, PARAMS, first.onSuccess, first.onError)
    dtFrameLogin(FRAME, PARAMS, second.onSuccess, second.onError)

    sdkCalls[0]!.onError('stale error')
    sdkCalls[1]!.onError('fresh error')

    expect(first.onError).not.toHaveBeenCalled()
    expect(second.onError).toHaveBeenCalledOnce()
    expect(second.onError).toHaveBeenCalledWith('fresh error')
  })
})

describe('loadDingTalkSdk', () => {
  it('SDK 已存在时直接完成,不插入 script', async () => {
    const { loadDingTalkSdk } = await importFresh()
    window.DTFrameLogin = vi.fn()
    await expect(loadDingTalkSdk()).resolves.toBeUndefined()
    expect(document.head.querySelector('script')).toBeNull()
  })

  it('并发调用共享同一次加载', async () => {
    const { loadDingTalkSdk } = await importFresh()
    const p1 = loadDingTalkSdk()
    const p2 = loadDingTalkSdk()
    const scripts = document.head.querySelectorAll('script')
    expect(scripts).toHaveLength(1)
    scripts[0]!.dispatchEvent(new Event('load'))
    await expect(Promise.all([p1, p2])).resolves.toBeDefined()
  })

  it('加载失败后清理 script,再次调用可重试', async () => {
    const { loadDingTalkSdk } = await importFresh()
    const p1 = loadDingTalkSdk()
    document.head.querySelector('script')!.dispatchEvent(new Event('error'))
    await expect(p1).rejects.toThrow('钉钉扫码组件加载失败')
    expect(document.head.querySelector('script')).toBeNull()

    const p2 = loadDingTalkSdk()
    const retryScript = document.head.querySelector('script')
    expect(retryScript).not.toBeNull()
    retryScript!.dispatchEvent(new Event('load'))
    await expect(p2).resolves.toBeUndefined()
  })
})
