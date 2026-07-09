import { describe, expect, it } from 'vitest'
import { formatToday, greetingForHour } from './greeting'

describe('greetingForHour', () => {
  it('上午 / 下午 / 晚上', () => {
    expect(greetingForHour(9)).toBe('上午好')
    expect(greetingForHour(14)).toBe('下午好')
    expect(greetingForHour(21)).toBe('晚上好')
  })
  it('边界：12 转下午，18 转晚上', () => {
    expect(greetingForHour(11)).toBe('上午好')
    expect(greetingForHour(12)).toBe('下午好')
    expect(greetingForHour(17)).toBe('下午好')
    expect(greetingForHour(18)).toBe('晚上好')
  })
})

describe('formatToday', () => {
  it('格式化为「年月日 星期」', () => {
    // 2026-07-09 为星期四（本地时区构造）
    expect(formatToday(new Date(2026, 6, 9))).toBe('2026年7月9日 星期四')
  })
})
