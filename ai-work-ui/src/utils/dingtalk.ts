// 钉钉内嵌扫码登录 SDK(DTFrameLogin)动态加载。
// 官方文档:https://open.dingtalk.com 「实现登录第三方网站」;
// 约束:内嵌页面必须与 redirect_uri 同源,且域名已登记在钉钉开放平台回调白名单
const SDK_URL = 'https://g.alicdn.com/dingding/h5-dingtalk-login/0.21.0/ddlogin.js'

export interface DTLoginSuccess {
  redirectUrl: string
  authCode: string
  state?: string
}

interface DTFrameConfig {
  id: string
  width?: number
  height?: number
}

interface DTLoginParams {
  redirect_uri: string
  client_id: string
  scope: string
  response_type: string
  state?: string
  prompt?: string
}

declare global {
  interface Window {
    DTFrameLogin?: (
      frame: DTFrameConfig,
      params: DTLoginParams,
      onSuccess: (result: DTLoginSuccess) => void,
      onError: (errorMsg: string) => void,
    ) => void
  }
}

// SDK 每次调用 DTFrameLogin 都会在 window 上新增 message 监听且从不移除;
// 浮层反复挂载会累积旧实例的监听器,与新实例争用同一个一次性 authCode。
// 用代次(epoch)让历史调用的回调失效:任何时刻仅最新一次 dtFrameLogin 的回调生效
let epoch = 0

export function dtFrameLogin(
  frame: DTFrameConfig,
  params: DTLoginParams,
  onSuccess: (result: DTLoginSuccess) => void,
  onError: (errorMsg: string) => void,
): void {
  if (!window.DTFrameLogin) throw new Error('钉钉扫码组件加载失败')
  epoch += 1
  const myEpoch = epoch
  window.DTFrameLogin(
    frame,
    params,
    (result) => {
      if (myEpoch === epoch) onSuccess(result)
    },
    (errorMsg) => {
      if (myEpoch === epoch) onError(errorMsg)
    },
  )
}

let sdkPromise: Promise<void> | null = null

export function loadDingTalkSdk(): Promise<void> {
  if (window.DTFrameLogin) return Promise.resolve()
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = SDK_URL
      script.onload = () => resolve()
      script.onerror = () => {
        // 失败后清理,允许下次重试
        script.remove()
        sdkPromise = null
        reject(new Error('钉钉扫码组件加载失败'))
      }
      document.head.appendChild(script)
    })
  }
  return sdkPromise
}
