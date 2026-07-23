import request from '@/utils/request'
import { BAAS_BASE, LONG_OP_CONFIG } from './base'
import type { CreatedProjectVO, ProjectVO, ReconcileReport, RotatedKey } from './types'

export function listProjects() {
  return request.get<ProjectVO[]>(`${BAAS_BASE}/studio/projects`)
}

// 建项目为长操作:provisioner 顺序执行建库/建账号/授权/初始化系统表多条 DDL、无端到端时限,
// 取消 client 超时以免慢开通在返回前 abort、丢失响应含的两枚一次性 key
export function createProject(name: string) {
  return request.post<CreatedProjectVO>(`${BAAS_BASE}/studio/projects`, { name }, LONG_OP_CONFIG)
}

export function getProject(ref: string) {
  return request.get<ProjectVO>(`${BAAS_BASE}/studio/projects/${ref}`)
}

// allowedOrigins:白名单字符串数组;null = 通配(允许全部来源);[] = 拒绝全部浏览器来源
// (二者语义相反,§7.7 通配/白名单二态)。PATCH 仅更新平台库、非长操作,不取消 client 超时。
export function patchProject(ref: string, allowedOrigins: string[] | null) {
  return request.patch<void>(`${BAAS_BASE}/studio/projects/${ref}`, { allowedOrigins })
}

export function deleteProject(ref: string) {
  return request.delete<void>(`${BAAS_BASE}/studio/projects/${ref}`)
}

// operationId 放 body(后端 ReconcileTriggerDTO);对账可执行不定长 DB 序列,取消 client 超时
export function reconcileProject(ref: string, operationId: string) {
  return request.post<ReconcileReport>(
    `${BAAS_BASE}/studio/projects/${ref}/reconcile`,
    { operationId },
    LONG_OP_CONFIG,
  )
}

// JWT 轮换:无 body,不走 operationId 幂等
export function rotateJwtKey(ref: string) {
  return request.post<RotatedKey>(`${BAAS_BASE}/studio/projects/${ref}/jwt-keys/rotate`)
}

export function emergencyRotateJwtKey(ref: string) {
  return request.post<RotatedKey>(`${BAAS_BASE}/studio/projects/${ref}/jwt-keys/emergency-rotate`)
}
