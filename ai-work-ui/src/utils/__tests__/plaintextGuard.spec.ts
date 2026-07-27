import { createApp, h, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { useLoginNavigationHold } from '../plaintextGuard'
import { loginNavigationHeld } from '../request'

function mount() {
  const visible = ref(false)
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({
    setup() {
      useLoginNavigationHold(visible)
      return () => h('div')
    },
  })
  app.mount(host)
  return { visible, unmount: () => app.unmount() }
}

describe('useLoginNavigationHold', () => {
  it('弹窗打开时挂起跳转,关闭后释放', async () => {
    const { visible, unmount } = mount()
    expect(loginNavigationHeld().holds).toBe(0)

    visible.value = true
    await nextTick()
    expect(loginNavigationHeld().holds).toBe(1)

    visible.value = false
    await nextTick()
    expect(loginNavigationHeld().holds).toBe(0)
    unmount()
  })

  // 明文弹窗开着时直接路由离开,若不在卸载时释放,计数泄漏会让此后所有 401 都不再跳转
  it('明文弹窗开着时组件卸载,计数一并释放', async () => {
    const { visible, unmount } = mount()
    visible.value = true
    await nextTick()
    expect(loginNavigationHeld().holds).toBe(1)

    unmount()
    expect(loginNavigationHeld().holds).toBe(0)
  })

  it('重复置为打开不重复计数', async () => {
    const { visible, unmount } = mount()
    visible.value = true
    await nextTick()
    visible.value = true
    await nextTick()
    expect(loginNavigationHeld().holds).toBe(1)
    unmount()
    expect(loginNavigationHeld().holds).toBe(0)
  })
})
