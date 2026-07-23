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
import type { AclConfig } from '@/api/baas/table'
import { newOperationId } from '@/api/baas/base'

const props = defineProps<{ refId: string }>()
const emit = defineEmits<{ saved: [] }>()

const open = ref(false)
const saving = ref(false)
const tableName = ref('')
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

async function openFor(name: string) {
  tableName.value = name
  const [aclRes, tableRes] = await Promise.all([
    getAcl(props.refId, name),
    getTable(props.refId, name),
  ])
  acl.value = aclRes.data.acl
  ownerColumn.value = aclRes.data.ownerColumn ?? ''
  // owner 列候选:bigint 且非主键 id(后端拒绝 ownerColumn=id)
  bigintColumns.value = tableRes.data.columns
    .filter((c) => c.dataType === 'bigint' && !c.pk)
    .map((c) => c.columnName)
  open.value = true
}

defineExpose({ openFor })

async function onSave() {
  saving.value = true
  try {
    const res = await putAcl(props.refId, tableName.value, {
      operationId: newOperationId(),
      acl: acl.value,
      ownerColumn: ownerColumn.value === '' ? null : ownerColumn.value,
    })
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
.hint {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}
</style>
