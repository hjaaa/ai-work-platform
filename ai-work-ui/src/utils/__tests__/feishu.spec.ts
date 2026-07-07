import { beforeEach, describe, expect, it, vi } from 'vitest'

// feishu.ts 持有模块级状态(sdkPromise),每个用例重新加载模块隔离
async function importFresh() {
  vi.resetModules()
  return await import('../feishu')
}

beforeEach(() => {
  delete window.QRLogin
  document.head.querySelectorAll('script').forEach((s) => s.remove())
})

describe('buildGotoUrl', () => {
  it('拼出飞书授权 URL 且参数正确编码', async () => {
    const { buildGotoUrl } = await importFresh()
    const url = buildGotoUrl('cli_test', 'http://localhost:5173/social-callback.html', 'abc123')
    const parsed = new URL(url)
    expect(parsed.origin).toBe('https://passport.feishu.cn')
    expect(parsed.pathname).toBe('/suite/passport/oauth/authorize')
    expect(parsed.searchParams.get('client_id')).toBe('cli_test')
    expect(parsed.searchParams.get('redirect_uri')).toBe(
      'http://localhost:5173/social-callback.html',
    )
    expect(parsed.searchParams.get('response_type')).toBe('code')
    expect(parsed.searchParams.get('state')).toBe('abc123')
  })
})

describe('createFeishuQr', () => {
  it('SDK 未加载时直接抛错', async () => {
    const { createFeishuQr } = await importFresh()
    expect(() => createFeishuQr({ id: 'qr', goto: 'https://example.com' })).toThrow(
      '飞书扫码组件加载失败',
    )
  })

  it('SDK 就绪时透传配置并返回实例', async () => {
    const { createFeishuQr } = await importFresh()
    const instance = { matchOrigin: vi.fn(), matchData: vi.fn() }
    window.QRLogin = vi.fn().mockReturnValue(instance)

    const result = createFeishuQr({ id: 'qr', goto: 'https://example.com', width: '300' })

    expect(window.QRLogin).toHaveBeenCalledWith({
      id: 'qr',
      goto: 'https://example.com',
      width: '300',
    })
    expect(result).toBe(instance)
  })
})

describe('parseCallbackMessage', () => {
  const VALID = {
    origin: window.location.origin,
    data: { source: 'social-login-callback', code: 'auth-code', state: 'expected' },
  }

  it('合法消息返回 code/state', async () => {
    const { parseCallbackMessage } = await importFresh()
    const msg = parseCallbackMessage(new MessageEvent('message', VALID), 'expected')
    expect(msg).toEqual({ source: 'social-login-callback', code: 'auth-code', state: 'expected' })
  })

  it('非同源消息丢弃', async () => {
    const { parseCallbackMessage } = await importFresh()
    const event = new MessageEvent('message', { ...VALID, origin: 'https://evil.example.com' })
    expect(parseCallbackMessage(event, 'expected')).toBeNull()
  })

  it('source 标记不符丢弃', async () => {
    const { parseCallbackMessage } = await importFresh()
    const event = new MessageEvent('message', {
      origin: window.location.origin,
      data: { source: 'other', code: 'auth-code', state: 'expected' },
    })
    expect(parseCallbackMessage(event, 'expected')).toBeNull()
  })

  it('state 不一致丢弃(防 CSRF)', async () => {
    const { parseCallbackMessage } = await importFresh()
    const event = new MessageEvent('message', VALID)
    expect(parseCallbackMessage(event, 'another-state')).toBeNull()
  })

  it('缺 code 丢弃', async () => {
    const { parseCallbackMessage } = await importFresh()
    const event = new MessageEvent('message', {
      origin: window.location.origin,
      data: { source: 'social-login-callback', code: '', state: 'expected' },
    })
    expect(parseCallbackMessage(event, 'expected')).toBeNull()
  })
})

describe('randomState', () => {
  it('生成 32 位十六进制且两次不同', async () => {
    const { randomState } = await importFresh()
    const a = randomState()
    const b = randomState()
    expect(a).toMatch(/^[0-9a-f]{32}$/)
    expect(a).not.toBe(b)
  })
})

describe('loadFeishuSdk', () => {
  it('QRLogin 已存在时不再注入脚本', async () => {
    const { loadFeishuSdk } = await importFresh()
    window.QRLogin = vi.fn()

    await loadFeishuSdk()

    expect(document.head.querySelector('script')).toBeNull()
  })

  it('加载失败后清理脚本并允许重试', async () => {
    const { loadFeishuSdk } = await importFresh()

    const first = loadFeishuSdk()
    const script = document.head.querySelector('script')!
    script.dispatchEvent(new Event('error'))
    await expect(first).rejects.toThrow('飞书扫码组件加载失败')
    expect(document.head.querySelector('script')).toBeNull()

    // 重试会重新注入脚本
    void loadFeishuSdk().catch(() => {})
    expect(document.head.querySelector('script')).not.toBeNull()
  })
})
