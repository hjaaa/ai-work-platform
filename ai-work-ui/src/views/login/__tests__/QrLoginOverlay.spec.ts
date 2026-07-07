import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const socialLogin = vi.fn()
const loadDingTalkSdk = vi.fn()
const dtFrameLogin = vi.fn()
const loadFeishuSdk = vi.fn()
const createFeishuQr = vi.fn()
const buildGotoUrl = vi.fn()
const parseCallbackMessage = vi.fn()
const randomState = vi.fn()

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

beforeEach(async () => {
  vi.resetModules()
  vi.clearAllMocks()
  vi.useFakeTimers()
  socialLogin.mockResolvedValue(undefined)
  loadDingTalkSdk.mockResolvedValue(undefined)
  loadFeishuSdk.mockResolvedValue(undefined)
  randomState.mockReturnValue('state-123')
  buildGotoUrl.mockReturnValue('https://passport.feishu.cn/auth?state=state-123')
  createFeishuQr.mockReturnValue({
    matchOrigin: vi.fn().mockImplementation((origin: string) => origin === 'https://passport.feishu.cn'),
    matchData: vi.fn().mockImplementation((data: unknown) => {
      return Boolean((data as { tmp_code?: string } | null)?.tmp_code)
    }),
  })
  parseCallbackMessage.mockReturnValue(null)
  vi.stubEnv('VITE_FEISHU_APP_ID', 'cli_test')
  QrLoginOverlay = (await import('../QrLoginOverlay.vue')).default
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('QrLoginOverlay 飞书扫码', () => {
  it('挂载时初始化飞书二维码', async () => {
    await mountOverlay()

    expect(loadFeishuSdk).toHaveBeenCalledOnce()
    expect(randomState).toHaveBeenCalledOnce()
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

  it('只接受合法回调消息并完成登录', async () => {
    await mountOverlay()

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
    await mountOverlay()
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
})
