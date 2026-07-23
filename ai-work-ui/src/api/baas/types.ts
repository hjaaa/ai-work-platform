// BaaS Studio 管理面契约类型。字段名与后端 record 组件名逐字一致(camelCase);
// 平台 Jackson 把 Long/long 序列化为 JSON string(防 JS 精度丢失),对应字段类型为 string。

export type ProjectStatus = 'PROVISIONING' | 'ACTIVE' | 'MIGRATING' | 'FAILED' | 'DELETING' | 'DELETED'
export type TableStatus = 'CREATING' | 'ACTIVE' | 'ALTERING' | 'FAILED' | 'CONFLICT' | 'DELETED'
export type KeyType = 'PUBLISHABLE' | 'SECRET'
export type KeyStatus = 'ACTIVE' | 'REVOKED'

export interface ProjectVO {
  projectRef: string
  name: string
  status: ProjectStatus
  allowedOrigins: string[] | null
  createTime: string // "yyyy-MM-dd HH:mm:ss"
}

export interface CreatedProjectVO {
  project: ProjectVO
  publishableKey: string // 明文仅创建响应返回一次
  secretKey: string
}

export interface ReconcileEntry {
  tableName: string
  reason: string
}

export interface ReconcileReport {
  corrected: ReconcileEntry[]
  imported: string[] // 表名字符串数组(后端 imported.add(key))
  recovered: ReconcileEntry[]
  conflicts: ReconcileEntry[]
  rejectedImports: ReconcileEntry[]
}

export interface RotatedKey {
  kid: string
}

export interface ApiKeyVO {
  id: string // 后端 String.valueOf(Long)
  keyType: KeyType
  keyPrefix: string // 明文前 12 字符(含 pub_/sec_ 前缀)
  status: KeyStatus
  createTime: string
}

export interface CreatedKeyVO {
  id: string
  keyType: KeyType
  plaintext: string // 明文仅此一次
}

export interface EndUserVO {
  id: string // Long → JSON string
  email: string
  createTime: string
  deletedAt: string | null // null = 在册;非 null = 软删时间
}

export interface UserPage {
  total: string // 后端 record 为 long,经 AiWorkLongModule 序列化为 string,展示前 Number() 转换
  records: EndUserVO[]
}
