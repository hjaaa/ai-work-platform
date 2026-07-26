<template>
  <div class="tables-tab">
    <div class="dc-card">
      <div class="table-head">
        <div class="table-title">表</div>
        <div class="head-actions">
          <span v-if="!projectActive" class="tombstone">项目非可用状态,Schema 操作已停用</span>
          <el-button @click="loadTables">刷新</el-button>
          <el-button v-if="projectActive" type="primary" @click="editorRef?.openCreate()">
            ＋ 新建表
          </el-button>
        </div>
      </div>
      <el-table v-loading="loading" :data="tables" style="width: 100%">
        <el-table-column prop="tableName" label="表名" min-width="180" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="TABLE_STATUS_MAP[row.status as TableStatus].type" disable-transitions>
              {{ TABLE_STATUS_MAP[row.status as TableStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerColumn" label="owner 列" width="140">
          <template #default="{ row }">{{ row.ownerColumn ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="comment" label="注释" min-width="160">
          <template #default="{ row }">{{ row.comment ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <span v-if="row.status === 'DELETED'" class="tombstone">删除保护期(同名禁重建)</span>
            <template v-else>
              <el-button
                v-if="canAlter(row.status)"
                link
                type="primary"
                @click="editorRef?.openEdit(row.tableName)"
              >
                编辑结构
              </el-button>
              <el-button
                v-if="canConfigAcl(row)"
                link
                type="primary"
                @click="aclRef?.openFor(row.tableName, row.status)"
              >
                ACL
              </el-button>
              <el-button v-if="canDrop(row.status)" link type="danger" @click="onDrop(row)">
                删表
              </el-button>
              <span v-if="!canAlter(row.status) && !canDrop(row.status)" class="tombstone">
                处理中,请刷新查看结果
              </span>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <TableEditorDrawer ref="editorRef" :ref-id="refId" @saved="loadTables" />
    <AclConfigDialog ref="aclRef" :ref-id="refId" @saved="loadTables" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, useTemplateRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dropTable, listTables } from '@/api/baas/table'
import type { TableSummary } from '@/api/baas/table'
import type { ProjectStatus, TableStatus } from '@/api/baas/types'
import { newOperationId } from '@/api/baas/base'
import { TABLE_STATUS_MAP } from '../statusMaps'
import TableEditorDrawer from './TableEditorDrawer.vue'
import AclConfigDialog from './AclConfigDialog.vue'

const props = defineProps<{ refId: string; projectStatus: ProjectStatus }>()

// 项目级门禁:TableManagementService.requireProjectActiveInLock 对建表/改表/删表一律要求
// 项目为 ACTIVE,否则 409「项目非 ACTIVE，禁止 Schema 操作」。项目不可用时整片操作都不呈现。
const projectActive = computed(() => props.projectStatus === 'ACTIVE')

// 表级门禁:按后端各操作的状态准入收敛按钮,避免呈现必然 409 的动作。
// 编辑结构:AlterTableWork.validateBranchStatus 对新 operationId 只放行 ACTIVE。
// ACL:AclConfigService 放行 ACTIVE,以及 CONFLICT 且本次为取消 owner 的 fail-closed 出口。
// 删表:TableManagementService 放行 ACTIVE / FAILED / CONFLICT。
// CREATING / ALTERING 处于流转中,三项均不可用。
function canAlter(status: TableStatus): boolean {
  return projectActive.value && status === 'ACTIVE'
}

// CONFLICT 下后端只放行 conflictCancel 分支,其判定是
// `dto.ownerColumn() == null && tableRow.getOwnerColumn() != null`——本就没有 owner 列的
// CONFLICT 表凑不出这个条件,对话框里任何保存都会 409,故该出口只对有 owner 列的表开放。
function canConfigAcl(row: TableSummary): boolean {
  if (!projectActive.value) return false
  return row.status === 'ACTIVE' || (row.status === 'CONFLICT' && row.ownerColumn !== null)
}

function canDrop(status: TableStatus): boolean {
  return projectActive.value && (status === 'ACTIVE' || status === 'FAILED' || status === 'CONFLICT')
}

const editorRef = useTemplateRef<InstanceType<typeof TableEditorDrawer>>('editorRef')
const aclRef = useTemplateRef<InstanceType<typeof AclConfigDialog>>('aclRef')

const tables = ref<TableSummary[]>([])
const loading = ref(false)

async function loadTables() {
  loading.value = true
  try {
    const res = await listTables(props.refId)
    tables.value = res.data ?? []
  } finally {
    loading.value = false
  }
}

async function onDrop(row: TableSummary) {
  try {
    await ElMessageBox.prompt(
      `删表为软删(tombstone),清理期内同名禁重建。请输入表名「${row.tableName}」以确认:`,
      '删除表',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
        inputValidator: (v: string) => v === row.tableName || '输入与表名不一致',
      },
    )
  } catch {
    return
  }
  const res = await dropTable(props.refId, row.tableName, newOperationId())
  ElMessage.success(`表已删除,物理清理时间:${res.data.deleteAfter}`)
  await loadTables()
}

onMounted(loadTables)
</script>

<style scoped>
.table-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px 8px;
}
.table-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--dc-ink);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tombstone {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}
</style>
