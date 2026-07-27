<template>
  <el-drawer v-model="open" :title="mode === 'create' ? '新建表' : `编辑结构:${snapshot?.tableName}`" size="70%">
    <div class="editor">
      <div class="meta-row">
        <el-form label-position="top" :inline="true" @submit.prevent>
          <el-form-item label="表名">
            <el-input v-model="tableName" placeholder="小写字母开头,[a-z0-9_],≤64" style="width: 260px" />
          </el-form-item>
          <el-form-item label="表注释">
            <el-input v-model="tableComment" maxlength="2048" placeholder="可选" style="width: 320px" />
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="visibleRows" style="width: 100%" class="col-table">
        <el-table-column label="列名" min-width="150">
          <template #default="{ row }">
            <span v-if="isIdRow(row)" class="id-cell">id(主键,自增)</span>
            <el-input v-else v-model="row.columnName" :disabled="row.dropped" placeholder="列名" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <span v-if="isIdRow(row)" class="id-cell">bigint</span>
            <el-select
              v-else
              v-model="row.dataType"
              :disabled="row.dropped"
              @change="resetFieldsForType(row)"
            >
              <el-option v-for="t in COLUMN_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="长度/精度" width="110">
          <template #default="{ row }">
            <el-input
              v-if="!isIdRow(row) && (row.dataType === 'varchar' || row.dataType === 'decimal')"
              v-model="row.lengthText"
              :disabled="row.dropped"
              :placeholder="row.dataType === 'varchar' ? '1~4096' : 'p 1~65'"
            />
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="标度" width="90">
          <template #default="{ row }">
            <el-input
              v-if="!isIdRow(row) && row.dataType === 'decimal'"
              v-model="row.scaleText"
              :disabled="row.dropped"
              placeholder="s"
            />
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="可空" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox v-if="!isIdRow(row)" v-model="row.nullable" :disabled="row.dropped" />
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="默认值" min-width="200">
          <template #default="{ row }">
            <!-- 勾选框表达「有无默认值」,与文本分离:varchar 的空串/前后空白是合法默认值 -->
            <div
              v-if="!isIdRow(row) && row.dataType !== 'text' && row.dataType !== 'json'"
              class="default-cell"
            >
              <el-checkbox v-model="row.hasDefault" :disabled="row.dropped" />
              <el-input
                v-model="row.defaultText"
                :disabled="row.dropped || !row.hasDefault"
                :placeholder="row.hasDefault ? '默认值' : '不勾选 = 无默认值'"
              />
            </div>
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="唯一" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox v-if="!isIdRow(row)" v-model="row.unique" :disabled="row.dropped" />
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="索引" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox
              v-if="!isIdRow(row)"
              v-model="row.indexed"
              :disabled="row.dropped || row.unique"
            />
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="注释" min-width="130">
          <template #default="{ row }">
            <el-input v-if="!isIdRow(row)" v-model="row.comment" :disabled="row.dropped" maxlength="1024" />
            <span v-else class="na">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <template v-if="!isIdRow(row)">
              <el-button v-if="row.original === null" link type="danger" @click="removeNewRow(row)">
                移除
              </el-button>
              <el-button v-else-if="!row.dropped" link type="danger" @click="row.dropped = true">
                删列
              </el-button>
              <el-button v-else link type="primary" @click="row.dropped = false">撤销删列</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="add-row">
        <el-button link type="primary" @click="addRow">＋ 添加列</el-button>
      </div>

      <el-alert
        v-if="errors.length > 0"
        type="error"
        :closable="false"
        show-icon
        class="error-box"
      >
        <div v-for="(err, i) in errors" :key="i">{{ err }}</div>
      </el-alert>
    </div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">提交</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alterTable, createTable, getTable } from '@/api/baas/table'
import type { TableAlterBody, TableCreateBody, TableSnapshot } from '@/api/baas/table'
import { extractBackendMsg, isDdlLockBusy, newOperationId } from '@/api/baas/base'
import {
  COLUMN_TYPES,
  blankRow,
  buildAlterBody,
  buildCreateBody,
  isAllowLossyRequired,
  matchPriorSubmission,
  resetFieldsForType,
  rowFromSnapshot,
  withAllowLossy,
} from '../tableEditor'
import type { EditorRow } from '../tableEditor'

const props = defineProps<{ refId: string }>()
const emit = defineEmits<{ saved: [] }>()

const open = ref(false)
const mode = ref<'create' | 'edit'>('create')
const snapshot = ref<TableSnapshot | null>(null)
const tableName = ref('')
const tableComment = ref('')
const rows = ref<EditorRow[]>([])
const errors = ref<string[]>([])
const submitting = ref(false)
let nextUid = 1

// id 主键行仅编辑态展示(不进模型行集合);用哨兵行渲染
const ID_ROW_UID = -1
const visibleRows = computed<EditorRow[]>(() => {
  const idRow = blankRow(ID_ROW_UID)
  idRow.columnName = 'id'
  idRow.dataType = 'bigint'
  return [idRow, ...rows.value]
})

function isIdRow(row: EditorRow): boolean {
  return row.uid === ID_ROW_UID
}

function addRow() {
  rows.value.push(blankRow(nextUid++))
}

function removeNewRow(row: EditorRow) {
  rows.value = rows.value.filter((r) => r.uid !== row.uid)
}

// 抽屉打开前表格仍可点,openEdit 的 getTable 在途时用户可以再点另一张表或「新建表」。
// 若不作废在途请求,先发后到的响应会用旧表的 snapshot/行集合覆盖当前选择,用户在 B 表的
// 界面上提交 A 表的结构。与 ACL 对话框同一处理:按序号丢弃过期响应,openCreate 也递增序号。
let loadSeq = 0

function openCreate() {
  loadSeq++
  lastSent = null
  mode.value = 'create'
  snapshot.value = null
  tableName.value = ''
  tableComment.value = ''
  rows.value = [blankRow(nextUid++)]
  errors.value = []
  open.value = true
}

async function openEdit(name: string) {
  const seq = ++loadSeq
  const res = await getTable(props.refId, name)
  if (seq !== loadSeq) return
  lastSent = null
  mode.value = 'edit'
  snapshot.value = res.data
  tableName.value = res.data.tableName
  tableComment.value = res.data.comment ?? ''
  rows.value = res.data.columns.filter((c) => !c.pk).map((c) => rowFromSnapshot(c, nextUid++))
  errors.value = []
  open.value = true
}

defineExpose({ openCreate, openEdit })

// 删掉 owner 列时,后端在快照里回 aclClosedByOwnerDrop=true——该表全部 anon/authenticated
// ACL 已被 fail-closed 关闭(与 ACL PUT 取消 owner 同一语义)。只报「改表成功」会让操作者
// 不知道表已对终端用户关闭访问,须显式提示。
function reportAlterResult(snapshot: TableSnapshot) {
  if (snapshot.aclClosedByOwnerDrop) {
    ElMessage.warning('改表成功;因删除了 owner 列,该表全部 anon/authenticated ACL 已被安全关闭,如需继续开放访问请重新配置 ACL')
    return
  }
  ElMessage.success('改表成功')
}

// ALTER 走到 DDL 引擎后失败会留下 FAILED 日志并把表置 CONFLICT。后端只在「同 operationId +
// 同 requestHash」时进 RETRY_FAILED 分支续跑(validateBranchStatus 仅该分支放行
// ALTERING/CONFLICT);每次提交换新 UUID 一律走 NEW_OPERATION,被「表当前状态不允许改表:
// CONFLICT」挡死,本可恢复的操作就此搁浅。响应丢失的情形同理:同 ID 才能重放 SUCCESS 快照。
// 因此记住上一次【实际发出】的提交体——内容未变的重试原样重发它(含同 operationId 与当时
// 已确认的 allowLossy),内容一改就换新 ID:后端 requireMatchingFingerprint 要求同 ID 同内容,
// 把旧 ID 复用到改动过的 body 上只会换来「同 operationId 的请求内容不一致」。
// 组装期 operationId 留空,由 matchPriorSubmission 判定复用旧 ID 还是取新 ID。
const OPERATION_ID_PENDING = ''
let lastSent: TableCreateBody | TableAlterBody | null = null

function withNewOperationId<T extends TableCreateBody | TableAlterBody>(body: T): T {
  return { ...body, operationId: newOperationId() }
}

async function onSubmit() {
  errors.value = []

  if (mode.value === 'create') {
    const result = buildCreateBody(tableName.value, tableComment.value, rows.value, OPERATION_ID_PENDING)
    if (!result.body) {
      errors.value = result.errors
      return
    }
    const prior = matchPriorSubmission(lastSent as TableCreateBody | null, [result.body])
    const body = prior ?? withNewOperationId(result.body)
    submitting.value = true
    try {
      lastSent = body
      await createTable(props.refId, body)
      ElMessage.success('建表成功')
      open.value = false
      emit('saved')
    } finally {
      submitting.value = false
    }
    return
  }

  // ===== 改表 =====
  const snap = snapshot.value!
  // 先以 allowLossy=false 组装;是否升级为 true 由删列预确认决定
  const first = buildAlterBody(
    snap,
    tableName.value,
    tableComment.value,
    rows.value,
    OPERATION_ID_PENDING,
    false,
  )
  if (!first.body) {
    errors.value = first.errors
    return
  }
  // allowLossy 的差异属于上一发当时已经确认过的放行,不算内容改动:重试沿用该确认不再二次弹窗
  const resend = matchPriorSubmission(lastSent as TableAlterBody | null, [
    first.body,
    withAllowLossy(first.body),
  ])
  if (resend !== null) {
    submitting.value = true
    try {
      await sendAlter(snap, resend)
    } finally {
      submitting.value = false
    }
    return
  }
  let body: TableAlterBody = first.body
  if (body.dropColumns && body.dropColumns.length > 0) {
    // allowLossy 是请求级开关:置 true 后,同批 modifyColumns 里的有损类型变更也会直接执行,
    // 后端不再回「有损类型变更须显式 allowLossy=true 确认」,下面那段列级确认因此不会触发。
    // 确认文案必须交代这个作用域,不能只列被删除的列。
    const modified = (body.modifyColumns ?? []).map((c) => c.columnName)
    const modifyNote =
      modified.length > 0
        ? `同批还将修改列:${modified.join('、')};allowLossy 为请求级开关,这些列上若存在有损类型变更` +
          '(如缩短 varchar、改窄数值类型),将一并直接执行且不再单独提示。'
        : ''
    try {
      await ElMessageBox.confirm(
        `将删除列:${body.dropColumns.join('、')}。删列不可恢复,确认后本次提交将允许有损变更(allowLossy=true)。${modifyNote}`,
        '有损操作确认',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    body = withAllowLossy(body)
  }

  submitting.value = true
  try {
    await sendAlter(snap, withNewOperationId(body))
  } finally {
    submitting.value = false
  }
}

async function sendAlter(snap: TableSnapshot, body: TableAlterBody) {
  try {
    lastSent = body
    reportAlterResult((await alterTable(props.refId, snap.tableName, body)).data)
    open.value = false
    emit('saved')
  } catch (e) {
    const msg = extractBackendMsg(e)
    if (!isAllowLossyRequired(msg)) {
      // 锁忙 409:拦截器已直显后端 msg,再补可操作引导(§7.7 409 分类);指纹/唯一键等非锁错误不追加
      if (isDdlLockBusy(e)) ElMessage.warning('操作冲突或锁忙,请刷新后重试')
      return
    }
    // 后端裁决存在有损 modify:确认后同 operationId 重发。该裁决在 validateInLock 阶段抛出,
    // 早于 DDL 日志落库,同 ID 换内容不会撞上 requireMatchingFingerprint。
    try {
      await ElMessageBox.confirm(`${msg}。确认执行该有损变更?`, '有损操作确认', {
        confirmButtonText: '确认执行',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    const lossy = withAllowLossy(body)
    lastSent = lossy
    reportAlterResult((await alterTable(props.refId, snap.tableName, lossy)).data)
    open.value = false
    emit('saved')
  }
}
</script>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.col-table :deep(.el-table__cell) {
  padding: 6px 0;
}
.id-cell {
  font-size: 13px;
  color: var(--dc-ink-disabled);
}
.na {
  color: var(--dc-ink-disabled);
}
.default-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
.add-row {
  padding: 4px 0;
}
.error-box {
  margin-top: 4px;
}
</style>
