import { AxiosError } from 'axios'
import type { AxiosRequestConfig } from 'axios'

// BaaS 服务路径前缀:微服务形态经网关为 /baas;单体(boot)形态部署时经 VITE_BAAS_PATH 覆盖为 /admin
export function resolveBaasBase(raw: string | undefined): string {
  const trimmed = (raw ?? '').replace(/^\/+|\/+$/g, '')
  return trimmed ? `/${trimmed}` : '/baas'
}

export const BAAS_BASE = resolveBaasBase(import.meta.env.VITE_BAAS_PATH)

// operationId:后端 OperationIdValidator 要求规范小写 UUID。
// secure context(HTTPS / localhost)用 crypto.randomUUID;但 §5 明文 HTTP 部署(http://gw:9999、
// http://host:9999)非 secure context,crypto.randomUUID 为 undefined——必须回退到基于
// crypto.getRandomValues 的 UUID v4 生成(该 API 不受 secure context 限制),否则 operationId
// 生成抛错,建/改/删表与 ACL/对账全部无法提交。
export function newOperationId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const bytes = new Uint8Array(16) as any
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10xx
  const hex = Array.from(bytes, (b: number) => b.toString(16).padStart(2, '0'))
  return `${hex[0]}${hex[1]}${hex[2]}${hex[3]}-${hex[4]}${hex[5]}-${hex[6]}${hex[7]}-${hex[8]}${hex[9]}-${hex[10]}${hex[11]}${hex[12]}${hex[13]}${hex[14]}${hex[15]}`
}

// DDL 类操作(建/改表、ACL 配置)走到引擎后失败会留下 FAILED 日志并把表置 CONFLICT。后端只在
// 「同 operationId + 同 requestHash」时进 RETRY_FAILED 分支续跑——AlterTableWork 的
// validateBranchStatus 仅该分支放行 ALTERING/CONFLICT,AclConfigService 的 retryableDdlState
// 同样要求 branch != NEW_OPERATION(才有 persistedDdlIntent)。每次提交换新 UUID 一律走
// NEW_OPERATION,被表状态挡死,本可恢复的操作就此搁浅;响应丢失时也只有同 ID 能重放 SUCCESS 快照。
// 用法:组装期 operationId 留空,发出前用 matchPriorSubmission 判定复用上一发还是取新 ID。
export function submissionKey(body: { operationId: string }): string {
  return JSON.stringify({ ...body, operationId: '' })
}

// 内容未变的重试:上一次【实际发出】的提交体与本次候选之一等价时,原样返回它以复用其
// operationId。内容一改就必须换新 ID——后端 requireMatchingFingerprint 要求同 ID 同内容,
// 把旧 ID 复用到改动过的 body 上只会换来「同 operationId 的请求内容不一致」。
export function matchPriorSubmission<T extends { operationId: string }>(
  prior: T | null,
  candidates: T[],
): T | null {
  if (prior === null) return null
  const priorKey = submissionKey(prior)
  return candidates.some((c) => submissionKey(c) === priorKey) ? prior : null
}

// 长操作(建/改/删表、ACL 配置、手动对账、建项目)取消 client 端 deadline。
// 后端此类操作无端到端时限(§7.7:ProjectProvisioner 顺序执行建库/建账号/授权/初始化系统表多条 DDL、
// 其 JdbcTemplate 无 queryTimeout;reconcile 可执行不定长 DB 序列),沿用 request.ts 默认 30s 超时会在
// 返回前 abort——丢失建项目响应含的两枚一次性 key,或把仍在提交的慢 ALTER / 对账误报为失败。
// timeout:0 取消 client deadline,由用户主动中止或连接断链兜底。
export const LONG_OP_CONFIG: AxiosRequestConfig = { timeout: 0 }

// 从请求异常中提取后端 R.msg(HTTP 4xx/5xx 时拦截器 reject 原 AxiosError,响应体为 R 结构)
export function extractBackendMsg(error: unknown): string {
  if (error instanceof AxiosError) {
    const data = error.response?.data as { msg?: string } | undefined
    return typeof data?.msg === 'string' ? data.msg : ''
  }
  return error instanceof Error ? error.message : ''
}

// 409 分类(§7.7):仅 DDL 锁忙(§9.2「该项目有 DDL 操作进行中」)可提示「刷新后重试」;
// 指纹不一致、有损 ALTER 数据不兼容、数据面唯一键冲突等非锁 409 一律直显后端 msg——
// 这些场景重试无效,提示刷新会把用户带入重试死循环。
export function isDdlLockBusy(error: unknown): boolean {
  return extractBackendMsg(error) === '该项目有 DDL 操作进行中'
}
