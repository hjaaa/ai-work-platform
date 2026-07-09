import { createApp, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BrandLogo from '@/components/BrandLogo.vue'

function mount(props: Record<string, unknown> = {}) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(BrandLogo, props) })
  app.mount(host)
  return {
    host,
    unmount: () => app.unmount(),
  }
}

let cleanup: (() => void) | null = null
let originalMatchMedia: typeof window.matchMedia | undefined
let hadMatchMedia = false

beforeEach(() => {
  hadMatchMedia = 'matchMedia' in window
  originalMatchMedia = window.matchMedia
  window.matchMedia = vi.fn().mockImplementation(() => ({
    matches: false,
    media: '(prefers-reduced-motion: reduce)',
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }))
})

afterEach(() => {
  cleanup?.()
  cleanup = null
  if (hadMatchMedia && originalMatchMedia) window.matchMedia = originalMatchMedia
  else window.matchMedia = vi.fn() as typeof window.matchMedia
})

describe('BrandLogo', () => {
  it('默认渲染品牌 logo 结构', () => {
    const { host, unmount } = mount()
    cleanup = unmount

    const container = host.querySelector('.brand-logo') as HTMLElement | null
    const svg = host.querySelector('svg')

    expect(container).not.toBeNull()
    expect(container?.style.width).toBe('64px')
    expect(container?.style.height).toBe('64px')
    expect(container?.style.borderRadius).toBe('18px')
    expect(svg?.getAttribute('width')).toBe('44')
    expect(svg?.getAttribute('height')).toBe('44')
    expect(host.querySelectorAll('[data-node]').length).toBe(4)
    expect(host.querySelectorAll('[data-edge]').length).toBe(5)
    expect(host.querySelectorAll('[data-pulse]').length).toBe(3)
  })

  it('size、svgSize、radius 控制外层尺寸与 svg 大小', () => {
    const { host, unmount } = mount({ size: 72, svgSize: 48, radius: 20 })
    cleanup = unmount

    const container = host.querySelector('.brand-logo') as HTMLElement | null
    const svg = host.querySelector('svg')

    expect(container?.style.width).toBe('72px')
    expect(container?.style.height).toBe('72px')
    expect(container?.style.borderRadius).toBe('20px')
    expect(svg?.getAttribute('width')).toBe('48')
    expect(svg?.getAttribute('height')).toBe('48')
  })
})
