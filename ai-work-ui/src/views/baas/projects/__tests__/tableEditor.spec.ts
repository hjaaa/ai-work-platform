import { describe, expect, it } from 'vitest'
import { RawNumber, stringifyExact } from '@/api/baas/rawNumber'
import type { ColumnSnapshot, TableSnapshot } from '@/api/baas/table'
import {
  COLUMN_TYPES,
  blankRow,
  buildAlterBody,
  buildCreateBody,
  isAllowLossyRequired,
  resetFieldsForType,
  rowFromSnapshot,
  withAllowLossy,
} from '../tableEditor'

const OP_ID = '018f6b2a-0000-4000-8000-000000000001'

function snapCol(partial: Partial<ColumnSnapshot> & { columnName: string; dataType: string }): ColumnSnapshot {
  return {
    length: undefined,
    scale: undefined,
    nullable: true,
    defaultValue: null,
    pk: false,
    autoIncrement: false,
    unique: false,
    indexed: false,
    comment: null,
    ...partial,
  }
}

function snapshot(columns: ColumnSnapshot[]): TableSnapshot {
  return {
    tableName: 'articles',
    comment: null,
    status: 'ACTIVE',
    ownerColumn: null,
    columns,
    acl: {
      anon: { select: false, insert: false, update: false, delete: false },
      authenticated: { select: false, insert: false, update: false, delete: false },
    },
  }
}

describe('buildCreateBody', () => {
  it('组装合法建表 body(varchar 带长度、boolean 默认值、缺省字段省略)', () => {
    const title = blankRow(1)
    title.columnName = 'title'
    title.dataType = 'varchar'
    title.lengthText = '255'
    title.nullable = false
    const published = blankRow(2)
    published.columnName = 'published'
    published.dataType = 'boolean'
    published.hasDefault = true
    published.defaultText = 'false'
    const result = buildCreateBody('articles', '文章表', [title, published], OP_ID)
    expect(result.errors).toEqual([])
    expect(result.body).toEqual({
      operationId: OP_ID,
      tableName: 'articles',
      comment: '文章表',
      columns: [
        { columnName: 'title', dataType: 'varchar', length: 255, nullable: false },
        { columnName: 'published', dataType: 'boolean', defaultValue: false },
      ],
    })
  })

  it('注释为空串时省略 comment 字段', () => {
    const c = blankRow(1)
    c.columnName = 'note'
    c.dataType = 'text'
    const result = buildCreateBody('articles', '', [c], OP_ID)
    expect(result.body).not.toHaveProperty('comment')
  })

  it('表名不合法 / 无列 → 报错', () => {
    expect(buildCreateBody('Bad-Name', '', [], OP_ID).errors.length).toBeGreaterThan(0)
    expect(buildCreateBody('articles', '', [], OP_ID).errors).toContain('至少需要一列')
  })

  it('列级校验:非法列名、id 保护、重名、varchar 缺长度、decimal 标度越界', () => {
    const bad = blankRow(1)
    bad.columnName = 'Bad'
    bad.dataType = 'int'
    expect(buildCreateBody('t1', '', [bad], OP_ID).errors[0]).toContain('列名不合法')

    const idRow = blankRow(1)
    idRow.columnName = 'id'
    idRow.dataType = 'bigint'
    expect(buildCreateBody('t1', '', [idRow], OP_ID).errors[0]).toContain('id')

    const a = blankRow(1)
    a.columnName = 'dup'
    a.dataType = 'int'
    const b = blankRow(2)
    b.columnName = 'dup'
    b.dataType = 'int'
    expect(buildCreateBody('t1', '', [a, b], OP_ID).errors[0]).toContain('重复')

    const v = blankRow(1)
    v.columnName = 'name'
    v.dataType = 'varchar'
    expect(buildCreateBody('t1', '', [v], OP_ID).errors[0]).toContain('varchar')

    const d = blankRow(1)
    d.columnName = 'price'
    d.dataType = 'decimal'
    d.lengthText = '10'
    d.scaleText = '31'
    expect(buildCreateBody('t1', '', [d], OP_ID).errors[0]).toContain('decimal')
  })

  it('decimal scale 文本非法(非空但无法解析)须报错,不得静默当作 0', () => {
    const negative = blankRow(1)
    negative.columnName = 'price'
    negative.dataType = 'decimal'
    negative.lengthText = '10'
    negative.scaleText = '-3'
    expect(buildCreateBody('t1', '', [negative], OP_ID).errors[0]).toContain('decimal')

    const nonNumeric = blankRow(1)
    nonNumeric.columnName = 'price'
    nonNumeric.dataType = 'decimal'
    nonNumeric.lengthText = '10'
    nonNumeric.scaleText = 'abc'
    expect(buildCreateBody('t1', '', [nonNumeric], OP_ID).errors[0]).toContain('decimal')
  })

  it('decimal/int 默认值以 RawNumber 承载原文,序列化后为裸 JSON 数值 token', () => {
    const dec = blankRow(1)
    dec.columnName = 'price'
    dec.dataType = 'decimal'
    dec.lengthText = '10'
    dec.scaleText = '2'
    dec.hasDefault = true
    dec.defaultText = '9.99'
    const num = blankRow(2)
    num.columnName = 'views'
    num.dataType = 'int'
    num.hasDefault = true
    num.defaultText = '0'
    const result = buildCreateBody('t1', '', [dec, num], OP_ID)
    expect(result.errors).toEqual([])
    expect(result.body!.columns[0]!.defaultValue).toBeInstanceOf(RawNumber)
    expect(result.body!.columns[1]!.defaultValue).toBeInstanceOf(RawNumber)
    // 后端 DefaultValueRenderer 对 decimal/int 断言 isNumber()/isIntegralNumber(),
    // 字符串 token 会被 400,故必须是不带引号的数值
    const json = JSON.parse(stringifyExact(result.body))
    expect(json.columns[0].defaultValue).toBe(9.99)
    expect(json.columns[1].defaultValue).toBe(0)
    expect(stringifyExact(result.body)).toContain('"defaultValue":9.99')
  })

  it('超 2^53 的 bigint 与高精度 decimal 默认值原文不失真', () => {
    const big = blankRow(1)
    big.columnName = 'seq'
    big.dataType = 'bigint'
    big.hasDefault = true
    big.defaultText = '9007199254740993'
    const precise = blankRow(2)
    precise.columnName = 'rate'
    precise.dataType = 'decimal'
    precise.lengthText = '40'
    precise.scaleText = '20'
    precise.hasDefault = true
    precise.defaultText = '1.00000000000000000001'
    const result = buildCreateBody('t1', '', [big, precise], OP_ID)
    expect(result.errors).toEqual([])
    const json = stringifyExact(result.body)
    // 经 Number() 会分别变成 9007199254740992 与 1
    expect(json).toContain('"defaultValue":9007199254740993')
    expect(json).toContain('"defaultValue":1.00000000000000000001')
  })

  it('前导零归一为合法 JSON 数值 token,负数与小数保持原文', () => {
    const row = (uid: number, type: string, text: string) => {
      const r = blankRow(uid)
      r.columnName = `c${uid}`
      r.dataType = type
      r.hasDefault = true
      r.defaultText = text
      if (type === 'decimal') {
        r.lengthText = '10'
        r.scaleText = '3'
      }
      return r
    }
    const result = buildCreateBody('t1', '', [row(1, 'int', '007'), row(2, 'decimal', '-007.500')], OP_ID)
    expect(result.errors).toEqual([])
    const json = stringifyExact(result.body)
    expect(json).toContain('"defaultValue":7')
    expect(json).toContain('"defaultValue":-7.500')
    // 归一后整体仍是合法 JSON(axios stringifySafely 靠 JSON.parse 判定是否原样透传)
    expect(() => JSON.parse(json)).not.toThrow()
  })

  it('varchar 默认值含 nonce 形态文本不影响数值 token 替换', () => {
    const text = blankRow(1)
    text.columnName = 'note'
    text.dataType = 'varchar'
    text.lengthText = '255'
    text.hasDefault = true
    text.defaultText = '__rawnum_0011223344556677__42__rawnum_0011223344556677__'
    const num = blankRow(2)
    num.columnName = 'views'
    num.dataType = 'int'
    num.hasDefault = true
    num.defaultText = '5'
    const result = buildCreateBody('t1', '', [text, num], OP_ID)
    expect(result.errors).toEqual([])
    const json = JSON.parse(stringifyExact(result.body))
    expect(json.columns[0].defaultValue).toBe(text.defaultText)
    expect(json.columns[1].defaultValue).toBe(5)
  })

  it('默认值类型化:int 整数、text/json 禁默认值、datetime CURRENT_TIMESTAMP 归一大写', () => {
    const n = blankRow(1)
    n.columnName = 'views'
    n.dataType = 'int'
    n.hasDefault = true
    n.defaultText = '0'
    const t = blankRow(2)
    t.columnName = 'created_at'
    t.dataType = 'datetime'
    t.hasDefault = true
    t.defaultText = 'current_timestamp'
    const result = buildCreateBody('t1', '', [n, t], OP_ID)
    expect(result.errors).toEqual([])
    expect(result.body!.columns[0]!.defaultValue).toEqual(new RawNumber('0'))
    expect(result.body!.columns[1]!.defaultValue).toBe('CURRENT_TIMESTAMP')

    const j = blankRow(1)
    j.columnName = 'meta'
    j.dataType = 'json'
    j.hasDefault = true
    j.defaultText = '{}'
    expect(buildCreateBody('t1', '', [j], OP_ID).errors[0]).toContain('默认值')

    const badInt = blankRow(1)
    badInt.columnName = 'views'
    badInt.dataType = 'int'
    badInt.hasDefault = true
    badInt.defaultText = 'abc'
    expect(buildCreateBody('t1', '', [badInt], OP_ID).errors[0]).toContain('默认值')
  })
})

describe('resetFieldsForType', () => {
  it('切到不支持 length/scale 的类型时清空残留,避免撞不可见字段的校验', () => {
    const row = blankRow(1)
    row.columnName = 'price'
    row.dataType = 'decimal'
    row.lengthText = '10'
    row.scaleText = '2'
    row.dataType = 'int'
    resetFieldsForType(row)
    expect(row.lengthText).toBe('')
    expect(row.scaleText).toBe('')
    expect(buildCreateBody('t1', '', [row], OP_ID).errors).toEqual([])
  })

  it('varchar 保留 length 但清 scale;切到 text/json 清默认值', () => {
    const v = blankRow(1)
    v.columnName = 'title'
    v.dataType = 'varchar'
    v.lengthText = '255'
    v.scaleText = '2'
    resetFieldsForType(v)
    expect(v.lengthText).toBe('255')
    expect(v.scaleText).toBe('')

    const t = blankRow(2)
    t.columnName = 'body'
    t.dataType = 'varchar'
    t.lengthText = '255'
    t.hasDefault = true
    t.defaultText = 'abc'
    t.dataType = 'text'
    resetFieldsForType(t)
    expect(t.hasDefault).toBe(false)
    expect(t.defaultText).toBe('')
    expect(buildCreateBody('t1', '', [t], OP_ID).errors).toEqual([])
  })
})

describe('buildAlterBody:操作推导', () => {
  it('加列/删列/改列/重命名各归位,未变行不产生操作', () => {
    const snap = snapshot([
      snapCol({ columnName: 'title', dataType: 'varchar', length: 255, nullable: false }),
      snapCol({ columnName: 'views', dataType: 'int' }),
      snapCol({ columnName: 'old_name', dataType: 'text' }),
      snapCol({ columnName: 'untouched', dataType: 'boolean' }),
    ])
    const rows = snap.columns.map((c, i) => rowFromSnapshot(c, i + 1))
    // 改列:title 扩长
    rows[0]!.lengthText = '512'
    // 删列:views
    rows[1]!.dropped = true
    // 重命名:old_name → new_name
    rows[2]!.columnName = 'new_name'
    // 加列
    const added = blankRow(99)
    added.columnName = 'added_col'
    added.dataType = 'bigint'
    const result = buildAlterBody(snap, '', '', [...rows, added], OP_ID, false)
    expect(result.errors).toEqual([])
    expect(result.body).toEqual({
      operationId: OP_ID,
      allowLossy: false,
      addColumns: [{ columnName: 'added_col', dataType: 'bigint' }],
      dropColumns: ['views'],
      modifyColumns: [{ columnName: 'title', dataType: 'varchar', length: 512, nullable: false }],
      renameColumns: [{ from: 'old_name', to: 'new_name' }],
    })
  })

  it('重命名 + 修改同行 → 互斥报错', () => {
    const snap = snapshot([snapCol({ columnName: 'a', dataType: 'int' })])
    const row = rowFromSnapshot(snap.columns[0]!, 1)
    row.columnName = 'b'
    row.dataType = 'bigint'
    const result = buildAlterBody(snap, '', '', [row], OP_ID, false)
    expect(result.body).toBeNull()
    expect(result.errors[0]).toContain('不能在一次提交中同时重命名与修改')
  })

  it('dropped 行忽略其余编辑;无任何修改 → 报错', () => {
    const snap = snapshot([snapCol({ columnName: 'a', dataType: 'int' })])
    const row = rowFromSnapshot(snap.columns[0]!, 1)
    row.dropped = true
    row.dataType = 'bigint' // 应被忽略
    const result = buildAlterBody(snap, '', '', [row], OP_ID, true)
    expect(result.body!.dropColumns).toEqual(['a'])
    expect(result.body).not.toHaveProperty('modifyColumns')

    const clean = rowFromSnapshot(snap.columns[0]!, 1)
    const noop = buildAlterBody(snap, '', '', [clean], OP_ID, false)
    expect(noop.body).toBeNull()
    expect(noop.errors).toContain('未做任何修改')
  })

  it('表重命名与注释变更进 body;与快照相同则省略', () => {
    const snap = snapshot([snapCol({ columnName: 'a', dataType: 'int' })])
    const row = rowFromSnapshot(snap.columns[0]!, 1)
    const renamed = buildAlterBody(snap, 'articles_v2', '新注释', [row], OP_ID, false)
    expect(renamed.body!.newTableName).toBe('articles_v2')
    expect(renamed.body!.comment).toBe('新注释')

    const same = buildAlterBody(snap, 'articles', '', [row], OP_ID, false)
    expect(same.body).toBeNull() // 表名/注释均未变、无列操作 → 未做任何修改
  })

  it('默认值文本往返不产生虚假 modify(boolean/数字/null 渲染基准一致)', () => {
    const snap = snapshot([
      snapCol({ columnName: 'flag', dataType: 'boolean', defaultValue: true }),
      snapCol({ columnName: 'views', dataType: 'int', defaultValue: 0 }),
    ])
    const rows = snap.columns.map((c, i) => rowFromSnapshot(c, i + 1))
    const result = buildAlterBody(snap, '', '', rows, OP_ID, false)
    expect(result.errors).toContain('未做任何修改')
  })

  it('defaultValue: null 的列往返不产生虚假 modify', () => {
    const snap = snapshot([
      snapCol({ columnName: 'note', dataType: 'varchar', length: 100, defaultValue: null }),
    ])
    const row = rowFromSnapshot(snap.columns[0]!, 1)
    const result = buildAlterBody(snap, '', '', [row], OP_ID, false)
    expect(result.errors).toContain('未做任何修改')
  })

  it('varchar 空串/前后空白默认值往返保真,改其他属性不抹掉也不重写默认值', () => {
    const snap = snapshot([
      snapCol({ columnName: 'a', dataType: 'varchar', length: 100, defaultValue: '' }),
      snapCol({ columnName: 'b', dataType: 'varchar', length: 100, defaultValue: ' x ' }),
    ])
    const rows = snap.columns.map((c, i) => rowFromSnapshot(c, i + 1))
    // 往返本身不产生虚假 modify
    expect(buildAlterBody(snap, '', '', rows, OP_ID, false).errors).toContain('未做任何修改')

    // 只改注释时,两列的默认值必须逐字带回,既不能丢 '' 也不能把 ' x ' 改写成 'x'
    rows[0]!.comment = '改注释'
    rows[1]!.comment = '改注释'
    const result = buildAlterBody(snap, '', '', rows, OP_ID, false)
    expect(result.errors).toEqual([])
    expect(result.body!.modifyColumns![0]!.defaultValue).toBe('')
    expect(result.body!.modifyColumns![1]!.defaultValue).toBe(' x ')
  })

  it('取消勾选默认值 → 视为改列并省略 defaultValue 字段', () => {
    const snap = snapshot([
      snapCol({ columnName: 'note', dataType: 'varchar', length: 100, defaultValue: 'hi' }),
    ])
    const row = rowFromSnapshot(snap.columns[0]!, 1)
    row.hasDefault = false
    const result = buildAlterBody(snap, '', '', [row], OP_ID, false)
    expect(result.errors).toEqual([])
    expect(result.body!.modifyColumns![0]).not.toHaveProperty('defaultValue')
  })

  it('rename 目标与其他未删除行重名 → 报错,不得静默产出重复列名', () => {
    const snap = snapshot([
      snapCol({ columnName: 'a', dataType: 'int' }),
      snapCol({ columnName: 'b', dataType: 'int' }),
    ])
    const rowA = rowFromSnapshot(snap.columns[0]!, 1)
    rowA.columnName = 'b'
    const rowB = rowFromSnapshot(snap.columns[1]!, 2)
    const result = buildAlterBody(snap, '', '', [rowA, rowB], OP_ID, false)
    expect(result.body).toBeNull()
    expect(result.errors.some((e) => e.includes('重复'))).toBe(true)
  })

  it('单独修改 unique/indexed/comment 触发 modifyColumns', () => {
    const snap = snapshot([snapCol({ columnName: 'email', dataType: 'varchar', length: 255 })])

    const uniqueRow = rowFromSnapshot(snap.columns[0]!, 1)
    uniqueRow.unique = true
    const uniqueResult = buildAlterBody(snap, '', '', [uniqueRow], OP_ID, false)
    expect(uniqueResult.errors).toEqual([])
    expect(uniqueResult.body!.modifyColumns).toEqual([
      { columnName: 'email', dataType: 'varchar', length: 255, unique: true },
    ])

    const indexedRow = rowFromSnapshot(snap.columns[0]!, 1)
    indexedRow.indexed = true
    const indexedResult = buildAlterBody(snap, '', '', [indexedRow], OP_ID, false)
    expect(indexedResult.errors).toEqual([])
    expect(indexedResult.body!.modifyColumns).toEqual([
      { columnName: 'email', dataType: 'varchar', length: 255, indexed: true },
    ])

    const commentRow = rowFromSnapshot(snap.columns[0]!, 1)
    commentRow.comment = '邮箱地址'
    const commentResult = buildAlterBody(snap, '', '', [commentRow], OP_ID, false)
    expect(commentResult.errors).toEqual([])
    expect(commentResult.body!.modifyColumns).toEqual([
      { columnName: 'email', dataType: 'varchar', length: 255, comment: '邮箱地址' },
    ])
  })
})

describe('isAllowLossyRequired / withAllowLossy', () => {
  it('识别两条后端消息原文', () => {
    expect(isAllowLossyRequired('删列为破坏性操作，须显式 allowLossy=true 确认')).toBe(true)
    expect(isAllowLossyRequired('有损类型变更须显式 allowLossy=true 确认: price')).toBe(true)
  })

  it('其他消息不误判', () => {
    expect(isAllowLossyRequired('同 operationId 的请求内容不一致')).toBe(false)
    expect(isAllowLossyRequired('')).toBe(false)
  })

  it('重发 body 仅 allowLossy 变化,operationId 与操作列表逐字复用', () => {
    const body = {
      operationId: OP_ID,
      allowLossy: false,
      dropColumns: ['a'],
      modifyColumns: [{ columnName: 'b', dataType: 'bigint' }],
    }
    const resent = withAllowLossy(body)
    expect(resent).toEqual({ ...body, allowLossy: true })
    expect(resent.operationId).toBe(OP_ID)
  })
})

describe('COLUMN_TYPES', () => {
  it('与后端 ColumnType 枚举一致', () => {
    expect([...COLUMN_TYPES]).toEqual([
      'int', 'bigint', 'decimal', 'varchar', 'text', 'json', 'boolean', 'date', 'datetime',
    ])
  })
})
