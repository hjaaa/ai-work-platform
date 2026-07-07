import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CryptoJS from 'crypto-js'
import { encryptPassword } from '../crypto'

// 与后端约定 key 与 IV 为同一 16 字节串
const TEST_KEY = 'abcdef0123456789'

// 按后端 PasswordDecoderFilter 的约定(AES/CFB/NoPadding,key 兼作 IV)解密
function decrypt(cipher: string, keyStr: string): string {
  const key = CryptoJS.enc.Utf8.parse(keyStr)
  return CryptoJS.AES.decrypt(cipher, key, {
    iv: key,
    mode: CryptoJS.mode.CFB,
    padding: CryptoJS.pad.NoPadding,
  }).toString(CryptoJS.enc.Utf8)
}

describe('encryptPassword', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_ENC_KEY', TEST_KEY)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('密文可按后端约定解密还原', () => {
    expect(decrypt(encryptPassword('P@ssw0rd!'), TEST_KEY)).toBe('P@ssw0rd!')
  })

  it('非块长整数倍的明文也能无填充加解密还原', () => {
    expect(decrypt(encryptPassword('abc'), TEST_KEY)).toBe('abc')
  })

  it('输出为 Base64 编码', () => {
    expect(encryptPassword('123456')).toMatch(/^[A-Za-z0-9+/]+={0,2}$/)
  })
})
