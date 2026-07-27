// 表结构编辑器领域模型:行状态、校验与建表/改表 body 组装(纯函数,无 Vue 依赖)。
// 改表契约为显式操作意图列表(spec §7.3):重命名 ≠ 删+加,不做全量 diff 推导。
import { RawNumber } from '@/api/baas/rawNumber'
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
const DECIMAL_RE = /^-?\d+(\.\d+)?$/
// 与后端 IndexAdmission.MAX_VARCHAR_INDEX_LENGTH 一致
const INDEXABLE_VARCHAR_MAX = 768
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
  // 默认值「是否存在」与「值文本」分离:varchar 的合法默认值可以是空串或含前后空白的字符串,
  // 用「文本为空」表示无默认值会让 DEFAULT '' 无法表达,并在改列时静默抹掉既有默认值。
  hasDefault: boolean
  defaultText: string
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

function snapshotHasDefault(value: unknown): boolean {
  return value !== null && value !== undefined
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
    hasDefault: snapshotHasDefault(col.defaultValue),
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
    hasDefault: false,
    defaultText: '',
    unique: false,
    indexed: false,
    comment: '',
  }
}

// 切换列类型后清空新类型不接受、且在编辑器里已被隐藏的字段。
// 否则残留值只会在提交时以「int 不接受 length/scale 参数」之类的报错出现,
// 而对应输入框已随类型隐藏,用户无从修改。
export function resetFieldsForType(row: EditorRow): void {
  if (row.dataType !== 'varchar' && row.dataType !== 'decimal') {
    row.lengthText = ''
  }
  if (row.dataType !== 'decimal') {
    row.scaleText = ''
  }
  if (row.dataType === 'text' || row.dataType === 'json') {
    row.hasDefault = false
    row.defaultText = ''
  }
}

interface ParsedDefault {
  value?: unknown // undefined = 无默认值(省略字段)
  error?: string
}

function parseDefault(row: EditorRow): ParsedDefault {
  if (!row.hasDefault) return {}
  // varchar 默认值原样保留(后端 varchar 分支只做长度校验,空串与前后空白都是合法值);
  // 其余类型是数值/字面量,前后空白无意义,先 trim 再匹配
  const text = row.dataType === 'varchar' ? row.defaultText : row.defaultText.trim()
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
      // 以 RawNumber 承载原文:int64 超 2^53 的部分经 Number() 会被静默舍入,后端照单全收
      if (!INTEGER_RE.test(text)) return { error: `列「${row.columnName}」:默认值须为整数` }
      return { value: new RawNumber(text) }
    case 'decimal':
      // 同样以 RawNumber 承载原文:后端只认 JSON 数值 token(字符串被 400),而 Number() 丢有效位
      if (!DECIMAL_RE.test(text)) return { error: `列「${row.columnName}」:默认值须为数字` }
      return { value: new RawNumber(text) }
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
  const scaleProvided = row.scaleText.trim() !== ''
  const scale = scaleProvided ? parsePositiveInt(row.scaleText) : null
  if (row.dataType === 'varchar') {
    if (length === null || length < 1 || length > 4096) {
      errors.push(`列「${name}」:varchar 长度须满足 1 <= n <= 4096`)
    }
    if (row.scaleText.trim() !== '') errors.push(`列「${name}」:varchar 不接受 scale`)
  } else if (row.dataType === 'decimal') {
    if (length === null || length < 1 || length > 65) {
      errors.push(`列「${name}」:decimal 精度 p 须满足 1 <= p <= 65`)
    } else if (scaleProvided && scale === null) {
      errors.push(`列「${name}」:decimal 小数位 s 须满足 0 <= s <= min(30, p)`)
    } else {
      const s = scale ?? 0
      if (s < 0 || s > Math.min(30, length)) {
        errors.push(`列「${name}」:decimal 小数位 s 须满足 0 <= s <= min(30, p)`)
      }
    }
  } else if (row.lengthText.trim() !== '' || row.scaleText.trim() !== '') {
    errors.push(`列「${name}」:${row.dataType} 不接受 length/scale 参数`)
  }
  // 索引准入(后端 IndexAdmission.validateColumnIndexRequest,spec §13):text/json 禁索引、
  // varchar 键长 length×4 ≤ 3072(即 length ≤ 768)。编辑器把 unique/indexed 呈现为可选项,
  // 不前置拦下的话这两种组合提交必 400。索引总数上限依赖最终结构,仍由后端裁决,此处不复刻。
  if (row.unique || row.indexed) {
    if (row.dataType === 'text' || row.dataType === 'json') {
      errors.push(`列「${name}」:${row.dataType} 不支持索引与唯一约束`)
    } else if (row.dataType === 'varchar' && length !== null && length > INDEXABLE_VARCHAR_MAX) {
      errors.push(
        `列「${name}」:varchar 建索引/唯一约束要求 length <= ${INDEXABLE_VARCHAR_MAX}(键长 length×4 <= 3072)`,
      )
    }
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
  // 注释逐字提交:后端 DdlRenderer 对非 null 注释原样转义写入 COMMENT,前后空白是有效内容。
  // 改列时 modifyColumns 会整列重定义,若在此 trim,改动列的任一其他属性都会把既有注释
  // 「 x 」静默改写成「x」、把纯空白注释抹成无注释。
  if (row.comment !== '') def.comment = row.comment
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
  // 表注释同样逐字提交(与改表路径口径一致,后者本就直发 comment 原文)
  if (comment !== '') body.comment = comment
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
    row.hasDefault !== snapshotHasDefault(o.defaultValue) ||
    // 无默认值时 defaultText 可能残留旧文本,不参与比较
    (row.hasDefault && row.defaultText !== defaultToText(o.defaultValue)) ||
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
  // 未删除行 alter 之后的最终列名(add 的新名/modify 与 unchanged 的原名/renamed 的目标名),用于重名检测
  const finalNames: string[] = []

  for (const row of rows) {
    if (row.original === null) {
      addRows.push(row)
      finalNames.push(row.columnName.trim())
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
      finalNames.push(row.columnName.trim())
      continue
    }
    if (modified) modifyRows.push(row)
    finalNames.push(row.original.columnName)
  }

  // 重名检测:add 的新名与 modify/unchanged/renamed 的最终名同属一个命名空间,不得重复(rule 2)
  const finalNameCounts = new Map<string, number>()
  for (const n of finalNames) finalNameCounts.set(n, (finalNameCounts.get(n) ?? 0) + 1)
  for (const [n, count] of finalNameCounts) {
    if (count > 1) errors.push(`列名「${n}」重复`)
  }

  // 重命名目标不得占用「提交前」已存在的列名——即便该列在同批被删除或被重命名走。
  // 后端按起始 schema 判定(AlterTableWork.validateRenameTargets 查 byName、staticValidate
  // 另禁「重命名目标同时参与其他列操作」),故「删 a + b→a」与「a/b 互换」都必然 400;
  // 最终名去重检测看不到这两种,须单独拦下并提示分两次提交。
  const snapshotNames = new Set(snapshot.columns.map((c) => c.columnName))
  for (const rename of renameColumns) {
    if (snapshotNames.has(rename.to)) {
      errors.push(
        `列「${rename.from}」的重命名目标「${rename.to}」在本次提交前已存在,` +
          `即使同批删除或改名也不允许占用,请分两次提交`,
      )
    }
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

// 提交体除 operationId 外的内容指纹,用于判定两次提交是否为「同一编辑意图」。
// 后端 requestHash 覆盖整个 body,所以内容一旦不同就必须换新 operationId。
export function submissionKey(body: TableCreateBody | TableAlterBody): string {
  return JSON.stringify({ ...body, operationId: '' })
}

// 内容未变的重试:上一次实际发出的提交体与本次候选之一等价时,原样返回它以复用 operationId。
// ALTER 失败会把表置 CONFLICT,后端仅在同 operationId + 同 requestHash 时进 RETRY_FAILED
// 分支续跑;换新 ID 只会走 NEW_OPERATION 并被表状态挡死。响应丢失时同 ID 才能重放 SUCCESS 快照。
export function matchPriorSubmission<T extends TableCreateBody | TableAlterBody>(
  prior: T | null,
  candidates: T[],
): T | null {
  if (prior === null) return null
  const priorKey = submissionKey(prior)
  return candidates.some((c) => submissionKey(c) === priorKey) ? prior : null
}
