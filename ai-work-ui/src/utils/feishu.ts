// 飞书网页扫码登录 SDK(QRLogin)动态加载与辅助函数。
// 官方文档:https://open.feishu.cn 「通过网页应用免密登录（扫码登录）」;
// 与钉钉不同:扫码后 SDK 经 window message 返回 tmp_code,需将其拼到授权 URL
// 由隐藏 iframe 完成 302,再经同源回调页 postMessage 取回正式 code
const SDK_URL =
  'https://lf-package-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.3.js'

const AUTHORIZE_URL = 'https://passport.feishu.cn/suite/passport/oauth/authorize'

export interface FeishuQrLogin {
  matchOrigin: (origin: string) => boolean
  matchData: (data: unknown) => boolean
}

interface QrLoginConfig {
  id: string
  goto: string
  width?: string
  height?: string
}

declare global {
  interface Window {
    QRLogin?: (config: QrLoginConfig) => FeishuQrLogin
  }
}

// 回调页 postMessage 的固定标记,与 public/social-callback.html 保持一致
export const CALLBACK_SOURCE = 'social-login-callback'

export interface SocialCallbackMessage {
  source: typeof CALLBACK_SOURCE
  code: string
  state: string
}

export function buildGotoUrl(appId: string, redirectUri: string, state: string): string {
  const params = new URLSearchParams({
    client_id: appId,
    redirect_uri: redirectUri,
    response_type: 'code',
    state,
  })
  return `${AUTHORIZE_URL}?${params.toString()}`
}

export function createFeishuQr(config: QrLoginConfig): FeishuQrLogin {
  if (!window.QRLogin) throw new Error('飞书扫码组件加载失败')
  return window.QRLogin(config)
}

// 判断消息是否为回调页发回的合法 code:同源 + source 标记 + state 一致(防 CSRF/伪造)
export function parseCallbackMessage(
  event: MessageEvent,
  expectedState: string,
): SocialCallbackMessage | null {
  if (event.origin !== window.location.origin) return null
  const data = event.data as Partial<SocialCallbackMessage> | null
  if (!data || data.source !== CALLBACK_SOURCE) return null
  if (!data.code || data.state !== expectedState) return null
  return data as SocialCallbackMessage
}

export function randomState(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

let sdkPromise: Promise<void> | null = null

export function loadFeishuSdk(): Promise<void> {
  if (window.QRLogin) return Promise.resolve()
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = SDK_URL
      script.onload = () => resolve()
      script.onerror = () => {
        // 失败后清理,允许下次重试
        script.remove()
        sdkPromise = null
        reject(new Error('飞书扫码组件加载失败'))
      }
      document.head.appendChild(script)
    })
  }
  return sdkPromise
}
