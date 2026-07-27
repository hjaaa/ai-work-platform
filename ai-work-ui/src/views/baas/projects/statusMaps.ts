import type { ProjectStatus, TableStatus } from '@/api/baas/types'

interface StatusMeta {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info' | 'primary'
}

export const PROJECT_STATUS_MAP: Record<ProjectStatus, StatusMeta> = {
  PROVISIONING: { label: '开通中', type: 'warning' },
  ACTIVE: { label: '运行中', type: 'success' },
  MIGRATING: { label: '系统表迁移中', type: 'warning' },
  FAILED: { label: '开通失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'info' },
  DELETED: { label: '已删除', type: 'info' },
}

export const TABLE_STATUS_MAP: Record<TableStatus, StatusMeta> = {
  CREATING: { label: '建表中', type: 'warning' },
  ACTIVE: { label: '可用', type: 'success' },
  ALTERING: { label: '改表中', type: 'warning' },
  FAILED: { label: '建表失败', type: 'danger' },
  CONFLICT: { label: '结构冲突', type: 'danger' },
  DELETED: { label: '已删除', type: 'info' },
}
