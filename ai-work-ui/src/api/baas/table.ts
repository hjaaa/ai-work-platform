import request from '@/utils/request'
import { BAAS_BASE, LONG_OP_CONFIG } from './base'
import { EXACT_JSON_HEADERS, stringifyExact } from './rawNumber'
import type { TableStatus } from './types'

// ===== 响应结构(后端 TableSnapshotBuilder / listTables 手工构建的 JSON,length/scale 为 number)=====

export interface TableSummary {
  tableName: string
  status: TableStatus
  comment: string | null
  ownerColumn: string | null
}

export interface ColumnSnapshot {
  columnName: string
  dataType: string
  length?: number
  scale?: number
  nullable: boolean
  defaultValue: unknown
  pk: boolean
  autoIncrement: boolean
  unique: boolean
  indexed: boolean
  comment: string | null
}

export interface AclRole {
  select: boolean
  insert: boolean
  update: boolean
  delete: boolean
}

export interface AclConfig {
  anon: AclRole
  authenticated: AclRole
}

export interface TableSnapshot {
  tableName: string
  comment: string | null
  status: TableStatus
  ownerColumn: string | null
  columns: ColumnSnapshot[]
  acl: AclConfig
  // 仅改表删掉 owner 列、后端 fail-closed 关闭全部 ACL 时出现(AlterTableWork.buildSnapshot)
  aclClosedByOwnerDrop?: boolean
}

export interface DropTableResult {
  tableName: string
  status: 'DELETED'
  deleteAfter: string
  cleanupOperationId: string
}

export interface AclSnapshot {
  tableName: string
  ownerColumn: string | null
  acl: AclConfig
  aclClosedByOwnerCancel?: boolean // 仅 PUT 且 owner 取消触发 fail-closed 关闭 ACL 时出现
}

// ===== 请求结构(与后端 record DTO 逐字对齐)=====

export interface ColumnDefinition {
  columnName: string
  dataType: string
  length?: number
  scale?: number
  nullable?: boolean // 后端缺省 true
  defaultValue?: unknown // JSON 标量;不设默认值则省略字段
  unique?: boolean // 后端缺省 false
  indexed?: boolean
  comment?: string
}

export interface ColumnRename {
  from: string
  to: string
}

export interface TableCreateBody {
  operationId: string
  tableName: string
  comment?: string
  columns: ColumnDefinition[]
}

export interface TableAlterBody {
  operationId: string
  allowLossy?: boolean
  newTableName?: string
  comment?: string
  addColumns?: ColumnDefinition[]
  dropColumns?: string[]
  modifyColumns?: ColumnDefinition[]
  renameColumns?: ColumnRename[]
}

export interface AclPutBody {
  operationId: string
  acl: AclConfig
  ownerColumn: string | null
}

// ===== 请求函数 =====

export function listTables(ref: string) {
  return request.get<TableSummary[]>(`${BAAS_BASE}/studio/projects/${ref}/tables`)
}

// 建表为长操作(走 §9.2 DDL 通道、无端到端时限),取消 client 超时;
// body 预序列化以保住数值默认值精度(见 rawNumber.ts)
export function createTable(ref: string, body: TableCreateBody) {
  return request.post<TableSnapshot>(`${BAAS_BASE}/studio/projects/${ref}/tables`, stringifyExact(body), {
    ...LONG_OP_CONFIG,
    headers: EXACT_JSON_HEADERS,
  })
}

export function getTable(ref: string, table: string) {
  return request.get<TableSnapshot>(`${BAAS_BASE}/studio/projects/${ref}/tables/${table}`)
}

// 改表为长操作(同步 ALTER 可执行至后端 DDL 超时),取消 client 超时;
// body 预序列化以保住数值默认值精度(见 rawNumber.ts)
export function alterTable(ref: string, table: string, body: TableAlterBody) {
  return request.patch<TableSnapshot>(
    `${BAAS_BASE}/studio/projects/${ref}/tables/${table}`,
    stringifyExact(body),
    { ...LONG_OP_CONFIG, headers: EXACT_JSON_HEADERS },
  )
}

// 删表的 operationId 走 query 参数(DELETE 不带 body,后端 @RequestParam("operationId"));
// 删表为长操作,合并 LONG_OP_CONFIG 取消 client 超时
export function dropTable(ref: string, table: string, operationId: string) {
  return request.delete<DropTableResult>(`${BAAS_BASE}/studio/projects/${ref}/tables/${table}`, {
    ...LONG_OP_CONFIG,
    params: { operationId },
  })
}

export function getAcl(ref: string, table: string) {
  return request.get<AclSnapshot>(`${BAAS_BASE}/studio/projects/${ref}/tables/${table}/acl`)
}

// ACL PUT 走项目级双层 DDL 锁、可能补建索引(§8.3),为长操作,取消 client 超时
export function putAcl(ref: string, table: string, body: AclPutBody) {
  return request.put<AclSnapshot>(
    `${BAAS_BASE}/studio/projects/${ref}/tables/${table}/acl`,
    body,
    LONG_OP_CONFIG,
  )
}
