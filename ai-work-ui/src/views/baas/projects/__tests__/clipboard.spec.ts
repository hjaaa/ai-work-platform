import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from '../clipboard'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
}))

const { ElMessage } = await import('element-plus')

function stubClipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true })
}

afterEach(() => {
  vi.clearAllMocks()
  Reflect.deleteProperty(navigator, 'clipboard')
  Reflect.deleteProperty(document, 'execCommand')
})

describe('copyText', () => {
  it('secure context:走 Async Clipboard API', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubClipboard({ writeText })
    await copyText('sk_live_x')
    expect(writeText).toHaveBeenCalledWith('sk_live_x')
    expect(ElMessage.success).toHaveBeenCalledWith('已复制')
  })

  // 明文 HTTP 部署下 navigator.clipboard 为 undefined,此前每次复制都落失败分支
  it('无 navigator.clipboard 时降级到 execCommand 并报成功', async () => {
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true })
    await copyText('sk_live_x')
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(ElMessage.success).toHaveBeenCalledWith('已复制')
    expect(ElMessage.error).not.toHaveBeenCalled()
    // 兜底用的 textarea 不得残留在文档里
    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })

  it('Clipboard API 抛错时同样降级', async () => {
    stubClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true })
    await copyText('sk_live_x')
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(ElMessage.success).toHaveBeenCalledWith('已复制')
  })

  it('两条路径都不可用时报失败', async () => {
    Object.defineProperty(document, 'execCommand', {
      value: vi.fn().mockReturnValue(false),
      configurable: true,
    })
    await copyText('sk_live_x')
    expect(ElMessage.error).toHaveBeenCalledWith('复制失败,请手动选择复制')
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
})
