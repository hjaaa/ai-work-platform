import request from '@/utils/request'
import { BAAS_BASE } from './base'
import type { ApiKeyVO, CreatedKeyVO, KeyType } from './types'

export function listKeys(ref: string) {
  return request.get<ApiKeyVO[]>(`${BAAS_BASE}/studio/projects/${ref}/keys`)
}

export function createKey(ref: string, keyType: KeyType) {
  return request.post<CreatedKeyVO>(`${BAAS_BASE}/studio/projects/${ref}/keys`, { keyType })
}

export function revokeKey(ref: string, keyId: string) {
  return request.post<void>(`${BAAS_BASE}/studio/projects/${ref}/keys/${keyId}/revoke`)
}
