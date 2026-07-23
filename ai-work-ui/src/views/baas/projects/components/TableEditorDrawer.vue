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
            <el-select v-else v-model="row.dataType" :disabled="row.dropped">
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
        <el-table-column label="默认值" min-width="140">
          <template #default="{ row }">
            <el-input
              v-if="!isIdRow(row) && row.dataType !== 'text' && row.dataType !== 'json'"
              v-model="row.defaultText"
              :disabled="row.dropped"
              placeholder="留空 = 无默认值"
            />
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
import type { TableAlterBody, TableSnapshot } from '@/api/baas/table'
import { extractBackendMsg, isDdlLockBusy, newOperationId } from '@/api/baas/base'
import {
  COLUMN_TYPES,
  blankRow,
  buildAlterBody,
  buildCreateBody,
  isAllowLossyRequired,
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

function openCreate() {
  mode.value = 'create'
  snapshot.value = null
  tableName.value = ''
  tableComment.value = ''
  rows.value = [blankRow(nextUid++)]
  errors.value = []
  open.value = true
}

async function openEdit(name: string) {
  const res = await getTable(props.refId, name)
  mode.value = 'edit'
  snapshot.value = res.data
  tableName.value = res.data.tableName
  tableComment.value = res.data.comment ?? ''
  rows.value = res.data.columns.filter((c) => !c.pk).map((c) => rowFromSnapshot(c, nextUid++))
  errors.value = []
  open.value = true
}

defineExpose({ openCreate, openEdit })

async function onSubmit() {
  errors.value = []
  const operationId = newOperationId() // 每次提交意图一个 ID,allowLossy 重发复用

  if (mode.value === 'create') {
    const result = buildCreateBody(tableName.value, tableComment.value, rows.value, operationId)
    if (!result.body) {
      errors.value = result.errors
      return
    }
    submitting.value = true
    try {
      await createTable(props.refId, result.body)
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
  const first = buildAlterBody(snap, tableName.value, tableComment.value, rows.value, operationId, false)
  if (!first.body) {
    errors.value = first.errors
    return
  }
  let body: TableAlterBody = first.body
  if (body.dropColumns && body.dropColumns.length > 0) {
    try {
      await ElMessageBox.confirm(
        `将删除列:${body.dropColumns.join('、')}。删列不可恢复,确认后本次提交将允许有损变更(allowLossy=true)。`,
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
    await alterTable(props.refId, snap.tableName, body)
    ElMessage.success('改表成功')
    open.value = false
    emit('saved')
  } catch (e) {
    const msg = extractBackendMsg(e)
    if (!isAllowLossyRequired(msg)) {
      // 锁忙 409:拦截器已直显后端 msg,再补可操作引导(§7.7 409 分类);指纹/唯一键等非锁错误不追加
      if (isDdlLockBusy(e)) ElMessage.warning('操作冲突或锁忙,请刷新后重试')
      return
    }
    // 后端裁决存在有损 modify:确认后同 operationId 重发
    try {
      await ElMessageBox.confirm(`${msg}。确认执行该有损变更?`, '有损操作确认', {
        confirmButtonText: '确认执行',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    await alterTable(props.refId, snap.tableName, withAllowLossy(body))
    ElMessage.success('改表成功')
    open.value = false
    emit('saved')
  } finally {
    submitting.value = false
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
.add-row {
  padding: 4px 0;
}
.error-box {
  margin-top: 4px;
}
</style>
