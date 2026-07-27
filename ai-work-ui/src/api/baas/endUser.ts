import request from '@/utils/request'
import { BAAS_BASE } from './base'
import type { UserPage } from './types'

// 分页参数名为 page/size(非 current);size 服务端钳制 1..100,page 超末页返回末页
export function listUsers(ref: string, page: number, size: number) {
  return request.get<UserPage>(`${BAAS_BASE}/studio/projects/${ref}/users`, {
    params: { page, size },
  })
}

// 软删:撤销该用户全部会话;已软删重复调用幂等成功
export function softDeleteUser(ref: string, userId: string) {
  return request.delete<void>(`${BAAS_BASE}/studio/projects/${ref}/users/${userId}`)
}

// 恢复:仅清 deleted_at,旧会话不复活;未软删调用幂等成功
export function restoreUser(ref: string, userId: string) {
  return request.post<void>(`${BAAS_BASE}/studio/projects/${ref}/users/${userId}/restore`)
}
