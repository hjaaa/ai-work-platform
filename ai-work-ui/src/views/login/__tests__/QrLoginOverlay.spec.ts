import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const socialLogin = vi.fn()
const loadDingTalkSdk = vi.fn()
const dtFrameLogin = vi.fn()
const loadFeishuSdk = vi.fn()
const createFeishuQr = vi.fn()
const buildGotoUrl = vi.fn()
const parseCallbackMessage = vi.fn()
const resolveRedirectUri = vi.fn()
const randomState = vi.fn()

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ socialLogin }),
}))

vi.mock('@/utils/dingtalk', () => ({
  loadDingTalkSdk,
  dtFrameLogin,
}))

vi.mock('@/utils/feishu', () => ({
  loadFeishuSdk,
  createFeishuQr,
  buildGotoUrl,
  parseCallbackMessage,
  resolveRedirectUri,
  randomState,
  CALLBACK_SOURCE: 'social-login-callback',
}))

async function mountOverlay(provider: 'dingtalk' | 'feishu' = 'feishu') {
  const emitted = { close: vi.fn(), success: vi.fn() }
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(QrLoginOverlay, {
    provider,
    onClose: emitted.close,
    onSuccess: emitted.success,
  })
  app.mount(host)
  await nextTick()
  await Promise.resolve()
  await nextTick()
  return {
    emitted,
    host,
    unmount: async () => {
      app.unmount()
      await nextTick()
      host.remove()
    },
  }
}

let QrLoginOverlay: typeof import('../QrLoginOverlay.vue').default
let cleanupMountedOverlays: Array<() => Promise<void>>

beforeEach(async () => {
  vi.resetModules()
  vi.clearAllMocks()
  vi.useFakeTimers()
  socialLogin.mockResolvedValue(undefined)
  loadDingTalkSdk.mockResolvedValue(undefined)
  loadFeishuSdk.mockResolvedValue(undefined)
  randomState.mockReturnValue('state-123')
  buildGotoUrl.mockImplementation(
    (_appId: string, _redirectUri: string, state: string) =>
      `https://passport.feishu.cn/auth?state=${state}`,
  )
  resolveRedirectUri.mockImplementation(
    (redirectUri?: string) => redirectUri || `${window.location.origin}/social-callback.html`,
  )
  createFeishuQr.mockReturnValue({
    matchOrigin: vi
      .fn()
      .mockImplementation((origin: string) => origin === 'https://passport.feishu.cn'),
    matchData: vi.fn().mockImplementation((data: unknown) => {
      return Boolean((data as { tmp_code?: string } | null)?.tmp_code)
    }),
  })
  parseCallbackMessage.mockImplementation(
    (event: MessageEvent, expectedState: string, expectedOrigin = window.location.origin) => {
      const data = event.data as
        | { source?: string; code?: string; state?: string }
        | null
        | undefined
      if (event.origin !== expectedOrigin) return null
      if (data?.source !== 'social-login-callback') return null
      if (!data.code || data.state !== expectedState) return null
      return { code: data.code, state: data.state }
    },
  )
  vi.stubEnv('VITE_FEISHU_APP_ID', 'cli_test')
  cleanupMountedOverlays = []
  QrLoginOverlay = (await import('../QrLoginOverlay.vue')).default
})

afterEach(async () => {
  while (cleanupMountedOverlays.length) {
    const cleanup = cleanupMountedOverlays.pop()
    if (cleanup) await cleanup()
  }
  vi.unstubAllEnvs()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('QrLoginOverlay 飞书扫码', () => {
  it('挂载时初始化飞书二维码', async () => {
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)

    expect(loadFeishuSdk).toHaveBeenCalledOnce()
    expect(randomState).toHaveBeenCalledOnce()
    expect(resolveRedirectUri).toHaveBeenCalledWith(undefined)
    expect(buildGotoUrl).toHaveBeenCalledWith(
      'cli_test',
      `${window.location.origin}/social-callback.html`,
      'state-123',
    )
    expect(createFeishuQr).toHaveBeenCalledWith({
      id: 'feishu-qr-login-frame',
      goto: 'https://passport.feishu.cn/auth?state=state-123',
      width: '300',
      height: '300',
    })
  })

  it('配置飞书回调地址时使用配置值并接收该来源回调', async () => {
    vi.stubEnv('VITE_FEISHU_REDIRECT_URI', 'http://localhost:5173/social-callback.html')
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)

    expect(resolveRedirectUri).toHaveBeenCalledWith('http://localhost:5173/social-callback.html')
    expect(buildGotoUrl).toHaveBeenCalledWith(
      'cli_test',
      'http://localhost:5173/social-callback.html',
      'state-123',
    )

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'http://localhost:5173',
        data: { source: 'social-login-callback', code: 'auth-code', state: 'state-123' },
      }),
    )
    await Promise.resolve()
    await nextTick()

    expect(parseCallbackMessage).toHaveBeenLastCalledWith(
      expect.any(MessageEvent),
      'state-123',
      'http://localhost:5173',
    )
    expect(socialLogin).toHaveBeenCalledWith('FEISHU', 'auth-code')
  })

  it('只接受合法回调消息并完成登录', async () => {
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://evil.example.com',
        data: { tmp_code: 'tmp-ignore' },
      }),
    )
    await nextTick()
    expect(document.querySelector('iframe')).toBeNull()
    expect(socialLogin).not.toHaveBeenCalled()

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://passport.feishu.cn',
        data: { tmp_code: 'tmp-ok' },
      }),
    )
    await nextTick()
    const iframe = document.querySelector('iframe')
    expect(iframe).not.toBeNull()
    expect(iframe?.getAttribute('src')).toBe(
      'https://passport.feishu.cn/auth?state=state-123&tmp_code=tmp-ok',
    )

    parseCallbackMessage.mockReturnValueOnce({ code: 'auth-code', state: 'state-123' })
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: window.location.origin,
        data: { source: 'social-login-callback', code: 'auth-code', state: 'state-123' },
      }),
    )
    await Promise.resolve()
    await nextTick()

    expect(socialLogin).toHaveBeenCalledWith('FEISHU', 'auth-code')
  })

  it('授权超时后重建二维码并清理隐藏 iframe', async () => {
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)
    const initialCreateCount = createFeishuQr.mock.calls.length

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://passport.feishu.cn',
        data: { tmp_code: 'tmp-timeout' },
      }),
    )
    await nextTick()
    expect(document.querySelector('iframe')).not.toBeNull()

    await vi.advanceTimersByTimeAsync(8000)
    await nextTick()

    expect(createFeishuQr.mock.calls.length).toBeGreaterThan(initialCreateCount)
    expect(document.body.textContent).toContain('授权超时，请刷新二维码重试')
    expect(document.querySelector('iframe')).toBeNull()
  })

  it('重建期间忽略旧 callback state', async () => {
    randomState.mockReturnValueOnce('state-old').mockReturnValueOnce('state-new')
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)

    const refreshDeferred = createDeferred<void>()
    loadFeishuSdk.mockReturnValueOnce(refreshDeferred.promise)
    const refreshButton = document.querySelector('.qr-refresh')
    expect(refreshButton).not.toBeNull()
    refreshButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: window.location.origin,
        data: { source: 'social-login-callback', code: 'old-code', state: 'state-old' },
      }),
    )
    await Promise.resolve()
    await nextTick()

    expect(socialLogin).not.toHaveBeenCalled()

    refreshDeferred.resolve()
    await Promise.resolve()
    await nextTick()

    expect(createFeishuQr).toHaveBeenLastCalledWith({
      id: 'feishu-qr-login-frame',
      goto: 'https://passport.feishu.cn/auth?state=state-new',
      width: '300',
      height: '300',
    })
  })

  it('初始化失败后不消费旧 tmp_code', async () => {
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)

    loadFeishuSdk.mockRejectedValueOnce(new Error('reload failed'))
    const refreshButton = document.querySelector('.qr-refresh')
    expect(refreshButton).not.toBeNull()
    refreshButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await Promise.resolve()
    await nextTick()

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://passport.feishu.cn',
        data: { tmp_code: 'tmp-stale' },
      }),
    )
    await nextTick()

    expect(document.querySelector('iframe')).toBeNull()
    expect(socialLogin).not.toHaveBeenCalled()
  })

  it('组件卸载后不再响应 message', async () => {
    const overlay = await mountOverlay()
    cleanupMountedOverlays.push(overlay.unmount)

    await overlay.unmount()
    cleanupMountedOverlays.pop()

    window.dispatchEvent(
      new MessageEvent('message', {
        origin: 'https://passport.feishu.cn',
        data: { tmp_code: 'tmp-after-unmount' },
      }),
    )
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: window.location.origin,
        data: { source: 'social-login-callback', code: 'code-after-unmount', state: 'state-123' },
      }),
    )
    await Promise.resolve()
    await nextTick()

    expect(document.querySelector('iframe')).toBeNull()
    expect(socialLogin).not.toHaveBeenCalled()
  })
})
