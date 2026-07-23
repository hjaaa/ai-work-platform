// 表结构编辑器领域模型:行状态、校验与建表/改表 body 组装(纯函数,无 Vue 依赖)。
// 改表契约为显式操作意图列表(spec §7.3):重命名 ≠ 删+加,不做全量 diff 推导。
import type {
  ColumnDefinition,
  ColumnSnapshot,
  TableAlterBody,
  TableCreateBody,
  TableSnapshot,
} from '@/api/baas/table'

export const COLUMN_TYPES = [
  'int', 'bigint', 'decimal', 'varchar', 'text', 'json', 'boolean', 'date', 'datetime',
] as const

// 与后端 IdentifierValidator 一致(保留字表不复刻,由后端兜底)
export const IDENTIFIER_RE = /^[a-z][a-z0-9_]{0,63}$/

const INTEGER_RE = /^-?\d+$/
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const DATETIME_RE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/

export interface EditorRow {
  uid: number
  original: ColumnSnapshot | null // null = 新增列
  dropped: boolean
  columnName: string
  dataType: string
  lengthText: string
  scaleText: string
  nullable: boolean
  defaultText: string // 空串 = 无默认值
  unique: boolean
  indexed: boolean
  comment: string
}

export interface BuildResult<T> {
  body: T | null
  errors: string[]
}

// 快照值 → 输入框文本(变化检测与回显共用同一渲染,避免类型歧义)
function defaultToText(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return String(value)
}

function intToText(value: number | undefined): string {
  return value === undefined || value === null ? '' : String(value)
}

export function rowFromSnapshot(col: ColumnSnapshot, uid: number): EditorRow {
  return {
    uid,
    original: col,
    dropped: false,
    columnName: col.columnName,
    dataType: col.dataType,
    lengthText: intToText(col.length),
    scaleText: intToText(col.scale),
    nullable: col.nullable,
    defaultText: defaultToText(col.defaultValue),
    unique: col.unique,
    indexed: col.indexed,
    comment: col.comment ?? '',
  }
}

export function blankRow(uid: number): EditorRow {
  return {
    uid,
    original: null,
    dropped: false,
    columnName: '',
    dataType: 'varchar',
    lengthText: '',
    scaleText: '',
    nullable: true,
    defaultText: '',
    unique: false,
    indexed: false,
    comment: '',
  }
}

interface ParsedDefault {
  value?: unknown // undefined = 无默认值(省略字段)
  error?: string
}

function parseDefault(row: EditorRow): ParsedDefault {
  const text = row.defaultText.trim()
  if (text === '') return {}
  switch (row.dataType) {
    case 'text':
    case 'json':
      return { error: `列「${row.columnName}」:${row.dataType} 不支持默认值` }
    case 'boolean':
      if (text === 'true') return { value: true }
      if (text === 'false') return { value: false }
      return { error: `列「${row.columnName}」:boolean 默认值只能为 true/false` }
    case 'int':
    case 'bigint':
      if (!INTEGER_RE.test(text)) return { error: `列「${row.columnName}」:默认值须为整数` }
      return { value: Number(text) }
    case 'decimal':
      // 以字符串提交(后端接受数字字符串双 token,保精度)
      if (!/^-?\d+(\.\d+)?$/.test(text)) return { error: `列「${row.columnName}」:默认值须为数字` }
      return { value: text }
    case 'date':
      if (!DATE_RE.test(text)) return { error: `列「${row.columnName}」:date 默认值格式须为 yyyy-MM-dd` }
      return { value: text }
    case 'datetime':
      if (text.toUpperCase() === 'CURRENT_TIMESTAMP') return { value: 'CURRENT_TIMESTAMP' }
      if (!DATETIME_RE.test(text)) {
        return { error: `列「${row.columnName}」:datetime 默认值须为 CURRENT_TIMESTAMP 或 yyyy-MM-dd HH:mm:ss` }
      }
      return { value: text }
    default:
      // varchar:原样字符串
      return { value: text }
  }
}

function parsePositiveInt(text: string): number | null {
  if (!/^\d+$/.test(text.trim()) || text.trim() === '') return null
  return Number(text.trim())
}

// 逐行校验;通过则返回 [],否则返回错误消息列表
function validateRow(row: EditorRow): string[] {
  const errors: string[] = []
  const name = row.columnName.trim()
  if (name === 'id') {
    errors.push('主键列 id 由服务端自动生成,不可自定义')
    return errors
  }
  if (!IDENTIFIER_RE.test(name)) {
    errors.push(`列名不合法:「${name || '(空)'}」须匹配小写字母开头的 [a-z][a-z0-9_]{0,63}`)
    return errors
  }
  if (!(COLUMN_TYPES as readonly string[]).includes(row.dataType)) {
    errors.push(`列「${name}」:不支持的类型 ${row.dataType}`)
    return errors
  }
  const length = row.lengthText.trim() === '' ? null : parsePositiveInt(row.lengthText)
  const scale = row.scaleText.trim() === '' ? null : parsePositiveInt(row.scaleText)
  if (row.dataType === 'varchar') {
    if (length === null || length < 1 || length > 4096) {
      errors.push(`列「${name}」:varchar 长度须满足 1 <= n <= 4096`)
    }
    if (row.scaleText.trim() !== '') errors.push(`列「${name}」:varchar 不接受 scale`)
  } else if (row.dataType === 'decimal') {
    if (length === null || length < 1 || length > 65) {
      errors.push(`列「${name}」:decimal 精度 p 须满足 1 <= p <= 65`)
    } else {
      const s = scale ?? 0
      if (s < 0 || s > Math.min(30, length)) {
        errors.push(`列「${name}」:decimal 小数位 s 须满足 0 <= s <= min(30, p)`)
      }
    }
  } else if (row.lengthText.trim() !== '' || row.scaleText.trim() !== '') {
    errors.push(`列「${name}」:${row.dataType} 不接受 length/scale 参数`)
  }
  const parsed = parseDefault(row)
  if (parsed.error) errors.push(parsed.error)
  return errors
}

// 已过校验的行 → ColumnDefinition(缺省语义字段省略:nullable=true、unique/indexed=false 不发)
function toColumnDefinition(row: EditorRow): ColumnDefinition {
  const def: ColumnDefinition = { columnName: row.columnName.trim(), dataType: row.dataType }
  const length = parsePositiveInt(row.lengthText)
  const scale = parsePositiveInt(row.scaleText)
  if (row.dataType === 'varchar' && length !== null) def.length = length
  if (row.dataType === 'decimal') {
    if (length !== null) def.length = length
    if (scale !== null) def.scale = scale
  }
  if (!row.nullable) def.nullable = false
  const parsed = parseDefault(row)
  if (parsed.value !== undefined) def.defaultValue = parsed.value
  if (row.unique) def.unique = true
  if (row.indexed) def.indexed = true
  if (row.comment.trim() !== '') def.comment = row.comment.trim()
  return def
}

function validateRows(rows: EditorRow[]): string[] {
  const errors: string[] = []
  const seen = new Set<string>()
  for (const row of rows) {
    if (row.dropped) continue
    errors.push(...validateRow(row))
    const name = row.columnName.trim()
    if (name && IDENTIFIER_RE.test(name)) {
      if (seen.has(name)) errors.push(`列名「${name}」重复`)
      seen.add(name)
    }
  }
  return errors
}

export function buildCreateBody(
  tableName: string,
  comment: string,
  rows: EditorRow[],
  operationId: string,
): BuildResult<TableCreateBody> {
  const errors: string[] = []
  const name = tableName.trim()
  if (!IDENTIFIER_RE.test(name) || name.startsWith('_')) {
    errors.push(`表名不合法:「${name || '(空)'}」须匹配小写字母开头的 [a-z][a-z0-9_]{0,63}`)
  }
  if (rows.length === 0) errors.push('至少需要一列')
  errors.push(...validateRows(rows))
  if (errors.length > 0) return { body: null, errors }
  const body: TableCreateBody = {
    operationId,
    tableName: name,
    columns: rows.map(toColumnDefinition),
  }
  if (comment.trim() !== '') body.comment = comment.trim()
  return { body, errors: [] }
}

// 行相对快照是否有(非重命名的)字段修改
function isModified(row: EditorRow): boolean {
  const o = row.original
  if (o === null) return false
  return (
    row.dataType !== o.dataType ||
    row.lengthText !== intToText(o.length) ||
    row.scaleText !== intToText(o.scale) ||
    row.nullable !== o.nullable ||
    row.defaultText !== defaultToText(o.defaultValue) ||
    row.unique !== o.unique ||
    row.indexed !== o.indexed ||
    row.comment !== (o.comment ?? '')
  )
}

export function buildAlterBody(
  snapshot: TableSnapshot,
  newTableName: string,
  comment: string,
  rows: EditorRow[],
  operationId: string,
  allowLossy: boolean,
): BuildResult<TableAlterBody> {
  const errors: string[] = []
  const addRows: EditorRow[] = []
  const dropColumns: string[] = []
  const modifyRows: EditorRow[] = []
  const renameColumns: { from: string; to: string }[] = []

  for (const row of rows) {
    if (row.original === null) {
      addRows.push(row)
      continue
    }
    if (row.dropped) {
      dropColumns.push(row.original.columnName)
      continue
    }
    const renamed = row.columnName.trim() !== row.original.columnName
    const modified = isModified(row)
    if (renamed && modified) {
      errors.push(
        `列「${row.original.columnName}」不能在一次提交中同时重命名与修改,请分两次提交`,
      )
      continue
    }
    if (renamed) {
      if (!IDENTIFIER_RE.test(row.columnName.trim()) || row.columnName.trim() === 'id') {
        errors.push(`列名不合法:「${row.columnName.trim()}」`)
        continue
      }
      renameColumns.push({ from: row.original.columnName, to: row.columnName.trim() })
      continue
    }
    if (modified) modifyRows.push(row)
  }

  // 仅对加列与改列行做字段级校验(重命名行/未变行/删除行不校验字段)
  errors.push(...validateRows([...addRows, ...modifyRows]))

  const tableRenamed = newTableName.trim() !== '' && newTableName.trim() !== snapshot.tableName
  if (tableRenamed && (!IDENTIFIER_RE.test(newTableName.trim()) || newTableName.trim().startsWith('_'))) {
    errors.push(`表名不合法:「${newTableName.trim()}」`)
  }
  const commentChanged = comment !== (snapshot.comment ?? '')

  const hasOps =
    addRows.length > 0 || dropColumns.length > 0 || modifyRows.length > 0 ||
    renameColumns.length > 0 || tableRenamed || commentChanged
  if (!hasOps) errors.push('未做任何修改')
  if (errors.length > 0) return { body: null, errors }

  const body: TableAlterBody = { operationId, allowLossy }
  if (tableRenamed) body.newTableName = newTableName.trim()
  if (commentChanged) body.comment = comment
  if (addRows.length > 0) body.addColumns = addRows.map(toColumnDefinition)
  if (dropColumns.length > 0) body.dropColumns = dropColumns
  if (modifyRows.length > 0) body.modifyColumns = modifyRows.map(toColumnDefinition)
  if (renameColumns.length > 0) body.renameColumns = renameColumns
  return { body, errors: [] }
}

// 后端 allowLossy 400 消息原文识别(AlterTableWork,注意全角逗号)
export function isAllowLossyRequired(msg: string): boolean {
  return (
    msg === '删列为破坏性操作，须显式 allowLossy=true 确认' ||
    msg.startsWith('有损类型变更须显式 allowLossy=true 确认: ')
  )
}

// allowLossy 重发:除 allowLossy 外逐字复用原 body(含同一 operationId)
export function withAllowLossy(body: TableAlterBody): TableAlterBody {
  return { ...body, allowLossy: true }
}
