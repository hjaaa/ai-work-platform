import CryptoJS from 'crypto-js'

// 与后端 PasswordDecoderFilter 约定：AES/CFB/NoPadding，key 与 IV 为同一 16 字节串
export function encryptPassword(plain: string): string {
  const key = CryptoJS.enc.Utf8.parse(import.meta.env.VITE_ENC_KEY)
  return CryptoJS.AES.encrypt(CryptoJS.enc.Utf8.parse(plain), key, {
    iv: key,
    mode: CryptoJS.mode.CFB,
    padding: CryptoJS.pad.NoPadding,
  }).toString() // Base64
}
