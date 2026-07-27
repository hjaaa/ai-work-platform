import { beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.fn(() => Promise.resolve({ code: 0, msg: '', data: null }))
const get = vi.fn(() => Promise.resolve({ code: 0, msg: '', data: null }))

vi.mock('@/utils/request', () => ({
  default: {
    get: (...args: unknown[]) => get(...(args as [])),
    post: (...args: unknown[]) => post(...(args as [])),
  },
}))

const { createKey, listKeys, revokeKey } = await import('../apiKey')

beforeEach(() => {
  vi.clearAllMocks()
})

describe('createKey', () => {
  // ProjectKeyService.createKey 先 lockActiveProject(项目行 FOR UPDATE)再落库,服务端无更短
  // 时限。若沿用 request.ts 的 30s client deadline,锁竞争下客户端会先 abort,而服务端随后仍
  // 可能提交这枚 active key——明文只回一次,响应被丢弃即永久失去该凭据。
  it('取消 client deadline(与建项目同为长操作)', async () => {
    await createKey('proj-1', 'SECRET')
    expect(post).toHaveBeenCalledWith(
      expect.stringContaining('/studio/projects/proj-1/keys'),
      { keyType: 'SECRET' },
      expect.objectContaining({ timeout: 0 }),
    )
  })
})

describe('其余 key 接口保留默认超时', () => {
  it('列表不属于长操作', async () => {
    await listKeys('proj-1')
    expect(get).toHaveBeenCalledWith(expect.stringContaining('/keys'))
  })

  it('吊销不返回明文,超时后重试即可', async () => {
    await revokeKey('proj-1', 'k-1')
    expect(post).toHaveBeenCalledWith(expect.stringContaining('/revoke'))
  })
})
