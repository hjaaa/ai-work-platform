import { describe, expect, it } from 'vitest'
import {
  resolveBaasBase,
  newOperationId,
  createSubmissionTracker,
  extractBackendMsg,
  isDdlLockBusy,
  matchPriorSubmission,
  submissionKey,
} from '../base'
import { AxiosError } from 'axios'
import type { InternalAxiosRequestConfig, AxiosResponse } from 'axios'

describe('resolveBaasBase', () => {
  it('未配置时默认 /baas', () => {
    expect(resolveBaasBase(undefined)).toBe('/baas')
    expect(resolveBaasBase('')).toBe('/baas')
  })

  it('boot 形态覆盖为 /admin', () => {
    expect(resolveBaasBase('/admin')).toBe('/admin')
  })

  it('归一化前后斜杠', () => {
    expect(resolveBaasBase('baas')).toBe('/baas')
    expect(resolveBaasBase('/baas/')).toBe('/baas')
    expect(resolveBaasBase('//admin//')).toBe('/admin')
  })
})

describe('newOperationId', () => {
  it('生成规范小写 UUID(后端 OperationIdValidator 要求)', () => {
    const id = newOperationId()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  })

  it('两次生成不相同', () => {
    expect(newOperationId()).not.toBe(newOperationId())
  })

  it('明文 HTTP 部署(非 secure context,crypto.randomUUID 为 undefined)回退 getRandomValues 仍产合法 UUID v4', () => {
    const original = crypto.randomUUID
    // 模拟 §5 明文 HTTP 部署:该 API 在非 secure context 不存在
    Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true })
    try {
      const id = newOperationId()
      // version nibble 恒为 4、variant 恒为 8/9/a/b
      expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    } finally {
      Object.defineProperty(crypto, 'randomUUID', { value: original, configurable: true })
    }
  })
})

describe('submissionKey / matchPriorSubmission', () => {
  const OP_ID = '018f6b2a-0000-4000-8000-000000000001'
  const OTHER_ID = '018f6b2a-0000-4000-8000-000000000002'
  const alter = (over: Partial<{ operationId: string; allowLossy: boolean; dropColumns: string[] }> = {}) => ({
    operationId: OP_ID,
    allowLossy: false,
    dropColumns: ['a'],
    ...over,
  })

  it('指纹忽略 operationId,只认内容', () => {
    expect(submissionKey(alter())).toBe(submissionKey(alter({ operationId: OTHER_ID })))
    expect(submissionKey(alter())).not.toBe(submissionKey(alter({ dropColumns: ['b'] })))
  })

  it('内容未变的重试复用上一发(含其 operationId)', () => {
    const sent = alter()
    expect(matchPriorSubmission(sent, [alter({ operationId: OTHER_ID })])).toBe(sent)
  })

  it('上一发已确认 allowLossy 时,重试沿用那一发而非降级为 false', () => {
    const sent = alter({ allowLossy: true })
    const baseline = alter({ operationId: OTHER_ID, allowLossy: false })
    // 表编辑器按 [基线, withAllowLossy(基线)] 两个候选匹配
    const matched = matchPriorSubmission(sent, [baseline, { ...baseline, allowLossy: true }])
    expect(matched).toBe(sent)
    expect(matched!.allowLossy).toBe(true)
  })

  it('内容改动后不复用,交由调用方取新 operationId', () => {
    expect(matchPriorSubmission(alter(), [alter({ dropColumns: ['a', 'b'] })])).toBeNull()
  })

  it('无上一发时返回 null', () => {
    expect(matchPriorSubmission(null, [alter()])).toBeNull()
  })

  it('ACL body 同样适用(泛型只约束 operationId)', () => {
    const acl = (select: boolean, operationId = OP_ID) => ({
      operationId,
      acl: { anon: { select, insert: false, update: false, delete: false } },
      ownerColumn: 'owner_id',
    })
    const sent = acl(true)
    expect(matchPriorSubmission(sent, [acl(true, OTHER_ID)])).toBe(sent)
    expect(matchPriorSubmission(sent, [acl(false, OTHER_ID)])).toBeNull()
  })
})

describe('createSubmissionTracker', () => {
  type Body = { operationId: string; tableName: string; allowLossy?: boolean }
  const draft = (tableName = 't1'): Body => ({ operationId: '', tableName })

  function sendOnce(tracker: ReturnType<typeof createSubmissionTracker<Body>>, body = draft()) {
    const resolved = tracker.resolve([body])
    tracker.markSent(resolved.body)
    return resolved
  }

  it('首次提交取新 operationId', () => {
    const tracker = createSubmissionTracker<Body>()
    const { body, reused } = tracker.resolve([draft()])
    expect(reused).toBe(false)
    expect(body.operationId).toMatch(/^[0-9a-f-]{36}$/)
  })

  // 建表失败后表留在 FAILED、改表/ACL 失败后留在 CONFLICT,只有原 operationId 能进 RETRY_FAILED。
  // 关掉表单重开(不换目标表)不得丢弃上一发,否则重新录入同样内容也无从恢复。
  it('失败后跨表单开关保留:同内容重试复用上一发的 ID', () => {
    const tracker = createSubmissionTracker<Body>()
    tracker.scopeTo('t1')
    const first = sendOnce(tracker)
    tracker.scopeTo('t1') // 关掉重开同一张表

    const retry = tracker.resolve([draft()])
    expect(retry.reused).toBe(true)
    expect(retry.body.operationId).toBe(first.body.operationId)
  })

  // 复用已 SUCCESS 的 ID 会让后端快速路径重放旧快照:一条 DDL 都不执行却报「成功」
  it('成功后作废:再提交同样内容取新 ID', () => {
    const tracker = createSubmissionTracker<Body>()
    tracker.scopeTo('t1')
    const first = sendOnce(tracker)
    tracker.markSucceeded()

    const again = tracker.resolve([draft()])
    expect(again.reused).toBe(false)
    expect(again.body.operationId).not.toBe(first.body.operationId)
  })

  // alter/ACL 的 body 不含表名(表名在 URL 上),沿用同 ID 会把上一张表的失败操作作用到新表
  it('换目标表即丢弃:即便内容字节相同也不复用', () => {
    const tracker = createSubmissionTracker<Body>()
    tracker.scopeTo('t1')
    const first = sendOnce(tracker)

    tracker.scopeTo('t2')
    const other = tracker.resolve([draft()]) // 内容与上一发完全相同
    expect(other.reused).toBe(false)
    expect(other.body.operationId).not.toBe(first.body.operationId)
  })

  it('内容改动后取新 ID', () => {
    const tracker = createSubmissionTracker<Body>()
    tracker.scopeTo('t1')
    const first = sendOnce(tracker)

    const changed = tracker.resolve([draft('renamed')])
    expect(changed.reused).toBe(false)
    expect(changed.body.operationId).not.toBe(first.body.operationId)
  })

  it('等价变体命中时原样返回上一发(保住已确认的 allowLossy)', () => {
    const tracker = createSubmissionTracker<Body>()
    tracker.scopeTo('t1')
    const baseline = { ...draft(), allowLossy: false }
    const sent = { ...tracker.resolve([baseline]).body, allowLossy: true }
    tracker.markSent(sent)

    // 重试从 allowLossy=false 的基线重新组装,按 [基线, 已确认变体] 匹配
    const retry = tracker.resolve([baseline, { ...baseline, allowLossy: true }])
    expect(retry.reused).toBe(true)
    expect(retry.body.allowLossy).toBe(true)
    expect(retry.body.operationId).toBe(sent.operationId)
  })
})

describe('isDdlLockBusy', () => {
  function axios409(msg: string): AxiosError {
    const config = {} as InternalAxiosRequestConfig
    return new AxiosError('Request failed', '409', config, null, {
      status: 409,
      statusText: '',
      data: { code: 1, msg, data: null },
      headers: {},
      config,
    } as AxiosResponse)
  }

  it('DDL 锁忙 409 命中(可提示刷新重试)', () => {
    expect(isDdlLockBusy(axios409('该项目有 DDL 操作进行中'))).toBe(true)
  })

  it('非锁 409(指纹不一致/有损 ALTER/唯一键冲突)不命中,应直显后端 msg 不误导重试', () => {
    expect(isDdlLockBusy(axios409('同 operationId 的请求内容不一致'))).toBe(false)
  })

  it('非 AxiosError 不命中', () => {
    expect(isDdlLockBusy(new Error('x'))).toBe(false)
  })
})

describe('extractBackendMsg', () => {
  function axiosErrorWith(data: unknown): AxiosError {
    const config = {} as InternalAxiosRequestConfig
    return new AxiosError('Request failed', '400', config, null, {
      status: 400,
      statusText: '',
      data,
      headers: {},
      config,
    } as AxiosResponse)
  }

  it('从 AxiosError 响应体提取 R.msg', () => {
    const err = axiosErrorWith({ code: 1, msg: '删列为破坏性操作，须显式 allowLossy=true 确认', data: null })
    expect(extractBackendMsg(err)).toBe('删列为破坏性操作，须显式 allowLossy=true 确认')
  })

  it('响应体无 msg 时返回空串', () => {
    expect(extractBackendMsg(axiosErrorWith({}))).toBe('')
    expect(extractBackendMsg(axiosErrorWith(undefined))).toBe('')
  })

  it('普通 Error 取 message,其他值返回空串', () => {
    expect(extractBackendMsg(new Error('boom'))).toBe('boom')
    expect(extractBackendMsg('x')).toBe('')
    expect(extractBackendMsg(null)).toBe('')
  })
})
