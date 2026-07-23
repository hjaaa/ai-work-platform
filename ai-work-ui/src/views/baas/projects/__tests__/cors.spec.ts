import { describe, expect, it } from 'vitest'
import { allowAllFromVO, corsPayload } from '../cors'

describe('allowed_origins 通配/白名单往返', () => {
  it('VO 回 null → 通配开关开启', () => {
    expect(allowAllFromVO(null)).toBe(true)
  })
  it('VO 回 [] → 开关关闭(拒绝全部,非通配)', () => {
    expect(allowAllFromVO([])).toBe(false)
  })
  it('VO 回非空数组 → 开关关闭(白名单)', () => {
    expect(allowAllFromVO(['https://a.com'])).toBe(false)
  })
  it('通配开关开启 → 提交 null', () => {
    expect(corsPayload(true, ['https://a.com'])).toBeNull()
  })
  it('开关关闭 → 提交白名单数组(空数组=拒绝全部,不塌成 null)', () => {
    expect(corsPayload(false, [])).toEqual([])
    expect(corsPayload(false, ['https://a.com'])).toEqual(['https://a.com'])
  })
})
