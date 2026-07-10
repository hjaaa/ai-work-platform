import { createApp, h } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import DcIcon from '@/components/DcIcon.vue'

type DcIconProps = {
  name: string
  size?: number
}

function mount(props: DcIconProps) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(DcIcon, props) })
  app.mount(host)
  return { host, unmount: () => app.unmount() }
}

let cleanup: (() => void) | null = null
afterEach(() => {
  cleanup?.()
  cleanup = null
})

describe('DcIcon', () => {
  it('已知 name 渲染对应 path', () => {
    const { host, unmount } = mount({ name: 'home' })
    cleanup = unmount
    const svg = host.querySelector('svg')!
    expect(svg).not.toBeNull()
    expect(svg.getAttribute('stroke-width')).toBe('1.6')
    expect(host.querySelectorAll('svg path').length).toBeGreaterThan(0)
  })

  it('未知 name 回退到默认图标而非空', () => {
    const { host, unmount } = mount({ name: '__nope__' })
    cleanup = unmount
    expect(host.querySelectorAll('svg path').length).toBeGreaterThan(0)
  })

  it('size 作用于宽高', () => {
    const { host, unmount } = mount({ name: 'bell', size: 24 })
    cleanup = unmount
    const svg = host.querySelector('svg')!
    expect(svg.getAttribute('width')).toBe('24')
  })
})
