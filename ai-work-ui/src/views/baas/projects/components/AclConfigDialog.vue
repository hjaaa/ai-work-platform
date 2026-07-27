<template>
  <el-dialog v-model="open" :title="`ACL 配置:${tableName}`" width="560px">
    <el-table :data="aclRows" style="width: 100%">
      <el-table-column prop="role" label="角色" width="140" />
      <el-table-column label="select" align="center">
        <template #default="{ row }"><el-checkbox v-model="row.acl.select" /></template>
      </el-table-column>
      <el-table-column label="insert" align="center">
        <template #default="{ row }"><el-checkbox v-model="row.acl.insert" /></template>
      </el-table-column>
      <el-table-column label="update" align="center">
        <template #default="{ row }"><el-checkbox v-model="row.acl.update" /></template>
      </el-table-column>
      <el-table-column label="delete" align="center">
        <template #default="{ row }"><el-checkbox v-model="row.acl.delete" /></template>
      </el-table-column>
    </el-table>

    <el-form label-position="top" class="owner-form" @submit.prevent>
      <el-form-item label="owner 列(行级归属,仅 bigint 列可选;取消 owner 将 fail-closed 关闭全部 anon/authenticated ACL)">
        <el-select v-model="ownerColumn" clearable placeholder="不启用 owner 策略" style="width: 100%">
          <el-option v-for="col in bigintColumns" :key="col" :label="col" :value="col" />
        </el-select>
      </el-form-item>
    </el-form>

    <!-- spec §7:未配置 owner_column 的表 = anon/authenticated 可访问全表,Studio 须明确提示该语义 -->
    <el-alert
      v-if="ownerColumn === ''"
      :type="hasAnyGrant ? 'warning' : 'info'"
      :closable="false"
      show-icon
      class="scope-alert"
    >
      未配置 owner 列:不做行级归属过滤,上方勾选的 anon/authenticated 权限将作用于
      <strong>该表全部行</strong>。
      {{ hasAnyGrant ? '当前已有勾选,保存后即对整表生效。' : '' }}
    </el-alert>

    <el-alert v-if="status === 'CONFLICT'" type="warning" :closable="false" show-icon class="scope-alert">
      表处于结构冲突状态:后端此时仅接受「取消 owner 列」这一 fail-closed 操作,其余 ACL 变更会被拒绝。
    </el-alert>

    <div class="hint">service_role(secret key)恒绕过 ACL 与 owner 策略,无须配置。</div>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAcl, getTable, putAcl } from '@/api/baas/table'
import type { AclConfig, AclPutBody } from '@/api/baas/table'
import type { TableStatus } from '@/api/baas/types'
import { createSubmissionTracker } from '@/api/baas/base'

const props = defineProps<{ refId: string }>()
const emit = defineEmits<{ saved: [] }>()

const open = ref(false)
const saving = ref(false)
const tableName = ref('')
const status = ref<TableStatus>('ACTIVE')
const ownerColumn = ref<string | ''>('')
const bigintColumns = ref<string[]>([])
const acl = ref<AclConfig>({
  anon: { select: false, insert: false, update: false, delete: false },
  authenticated: { select: false, insert: false, update: false, delete: false },
})

const aclRows = computed(() => [
  { role: 'anon', acl: acl.value.anon },
  { role: 'authenticated', acl: acl.value.authenticated },
])

const hasAnyGrant = computed(() =>
  aclRows.value.some((r) => r.acl.select || r.acl.insert || r.acl.update || r.acl.delete),
)

// 连续对不同表点 ACL 时,先发的请求可能后返回。若不丢弃过期响应,对话框会显示 A 表的
// ACL/owner 而保存目标已是 B 表,onSave 就把 A 的权限写到 B 上。以序号标记每次打开,
// 只有最后一次的响应可以落到状态上;tableName 也一并推迟到此刻赋值,与数据保持同批。
let loadSeq = 0

async function openFor(name: string, tableStatus: TableStatus) {
  const seq = ++loadSeq
  const [aclRes, tableRes] = await Promise.all([
    getAcl(props.refId, name),
    getTable(props.refId, name),
  ])
  if (seq !== loadSeq) return
  submissions.scopeTo(name)
  tableName.value = name
  status.value = tableStatus
  acl.value = aclRes.data.acl
  ownerColumn.value = aclRes.data.ownerColumn ?? ''
  // owner 列候选:bigint 且非主键 id(后端拒绝 ownerColumn=id)
  bigintColumns.value = tableRes.data.columns
    .filter((c) => c.dataType === 'bigint' && !c.pk)
    .map((c) => c.columnName)
  open.value = true
}

defineExpose({ openFor })

// 指定 owner 列时若缺少可用单列索引,ACL 配置会补建索引;取得所有权后再失败,
// AclConfigService.onFailureTx 把表置 CONFLICT。此后 validateInLock 只认 retryableDdlState
// ——它要求 branch != NEW_OPERATION 才有 persistedDdlIntent,而换新 operationId 必然是
// NEW_OPERATION,于是撞上「表当前状态不允许 ACL 配置: CONFLICT」,本可续跑的补建索引搁浅。
// 与表结构编辑器同一处理:记住上一次实际发出的 body,内容未变的重试原样重发以复用其 ID
// (生命周期三条规则见 createSubmissionTracker 的注释)。
const submissions = createSubmissionTracker<AclPutBody>()

async function onSave() {
  const draft: AclPutBody = {
    operationId: '',
    // acl.value 是响应式对象:直接引用会让上一发随后续勾选一起变,指纹恒等于当前值,
    // 内容改了也判成「未变」而复用旧 ID,反被后端指纹校验拒。取值快照切断引用。
    acl: JSON.parse(JSON.stringify(acl.value)) as AclConfig,
    ownerColumn: ownerColumn.value === '' ? null : ownerColumn.value,
  }
  const { body } = submissions.resolve([draft])
  saving.value = true
  try {
    submissions.markSent(body)
    const res = await putAcl(props.refId, tableName.value, body)
    submissions.markSucceeded()
    if (res.data.aclClosedByOwnerCancel) {
      ElMessage.warning('已取消 owner 配置,全部 anon/authenticated ACL 已被安全关闭')
    } else {
      ElMessage.success('ACL 已保存')
    }
    open.value = false
    emit('saved')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.owner-form {
  margin-top: 16px;
}
.scope-alert {
  margin-bottom: 8px;
}
.hint {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}
</style>
