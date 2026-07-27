import request from '@/utils/request'
import { BAAS_BASE, LONG_OP_CONFIG } from './base'
import type { ApiKeyVO, CreatedKeyVO, KeyType } from './types'

export function listKeys(ref: string) {
  return request.get<ApiKeyVO[]>(`${BAAS_BASE}/studio/projects/${ref}/keys`)
}

// 与建项目同为长操作:ProjectKeyService.createKey 先 lockActiveProject(项目行 FOR UPDATE)
// 再落库,服务端无更短时限。沿用 30s client deadline 会在锁竞争下先行 abort,而服务端随后
// 仍可能提交这枚 active key——明文响应被丢弃,库里多出一枚无人知晓明文的有效凭据,UI 却报失败。
export function createKey(ref: string, keyType: KeyType) {
  return request.post<CreatedKeyVO>(
    `${BAAS_BASE}/studio/projects/${ref}/keys`,
    { keyType },
    LONG_OP_CONFIG,
  )
}

export function revokeKey(ref: string, keyId: string) {
  return request.post<void>(`${BAAS_BASE}/studio/projects/${ref}/keys/${keyId}/revoke`)
}
