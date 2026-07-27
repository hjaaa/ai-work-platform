import { onUnmounted, watch } from 'vue'
import type { Ref } from 'vue'
import { holdLoginNavigation } from './request'

/**
 * 一次性明文（建项目/建 Key 的密钥）在屏期间挂起 401/424 的登录跳转。
 *
 * 后端对这些明文只回一次、不可再查。若此刻有请求返回 401/424，request.ts 的硬跳转会
 * 卸载页面、连同尚未保存的明文一起销毁。只推迟组件自己发起的刷新挡不住此前已在途、
 * 迟到才落地的请求，故把守卫下沉到拦截器：明文在屏就挂起跳转，关闭后再放行。
 *
 * @param visible 明文弹窗的可见性 ref；组件卸载时一并释放，避免计数泄漏后永不跳转
 */
export function useLoginNavigationHold(visible: Ref<boolean>) {
  let release: (() => void) | null = null

  watch(visible, (open) => {
    if (open && release === null) {
      release = holdLoginNavigation()
      return
    }
    if (!open && release !== null) {
      release()
      release = null
    }
  })

  onUnmounted(() => {
    release?.()
    release = null
  })
}
