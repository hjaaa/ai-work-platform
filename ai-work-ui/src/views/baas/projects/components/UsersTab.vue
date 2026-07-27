<template>
  <div class="users-tab">
    <el-alert
      v-if="gateBlocked"
      type="warning"
      :closable="false"
      show-icon
      title="项目系统表升级未完成,终端用户管理暂不可用;请等待后台迁移完成或联系 baas_admin 手动触发迁移"
      class="gate-alert"
    />
    <div class="dc-card">
      <div class="table-head">
        <div class="table-title">终端用户</div>
        <el-button @click="loadUsers">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="users" style="width: 100%">
        <el-table-column prop="id" label="ID" width="180" />
        <el-table-column prop="email" label="邮箱" min-width="220" />
        <el-table-column prop="createTime" label="注册时间" width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.deletedAt === null ? 'success' : 'info'" disable-transitions>
              {{ row.deletedAt === null ? '在册' : '已软删' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.deletedAt === null" link type="danger" @click="onSoftDelete(row)">
              软删
            </el-button>
            <el-button v-else link type="primary" @click="onRestore(row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="onPageChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listUsers, restoreUser, softDeleteUser } from '@/api/baas/endUser'
import type { EndUserVO } from '@/api/baas/types'
import { extractBackendMsg } from '@/api/baas/base'

const props = defineProps<{ refId: string }>()

const users = ref<EndUserVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const gateBlocked = ref(false)

const GATE_MSG = '系统表升级未完成,项目当前不允许终端用户管理操作'

// 分页器连点会并发多次 loadUsers,响应可能乱序返回。若不作废过期响应,分页器停在第 3 页
// 而表格显示第 2 页的数据,软删/恢复这类破坏性操作就会呈现在与页码不符的行上。
let loadSeq = 0

async function loadUsers() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const res = await listUsers(props.refId, page.value, size)
    if (seq !== loadSeq) return
    users.value = res.data.records
    total.value = Number(res.data.total) // 后端 long → JSON string
    gateBlocked.value = false
  } catch (e) {
    if (seq !== loadSeq) return
    if (extractBackendMsg(e) === GATE_MSG) gateBlocked.value = true
  } finally {
    // 过期请求不得替在途的最新请求清掉 loading
    if (seq === loadSeq) loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  loadUsers()
}

async function onSoftDelete(row: EndUserVO) {
  try {
    await ElMessageBox.confirm(
      `软删用户「${row.email}」将立即撤销其全部会话;邮箱唯一键不释放(同邮箱不可重新注册)。已签发的 access JWT 在 TTL 内仍可访问数据面。确认软删?`,
      '软删终端用户',
      { confirmButtonText: '软删', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await softDeleteUser(props.refId, row.id)
  ElMessage.success('已软删')
  await loadUsers()
}

async function onRestore(row: EndUserVO) {
  try {
    await ElMessageBox.confirm(
      `恢复用户「${row.email}」后可重新登录;旧会话不会复活。确认恢复?`,
      '恢复终端用户',
      { confirmButtonText: '恢复', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await restoreUser(props.refId, row.id)
  ElMessage.success('已恢复')
  await loadUsers()
}

onMounted(loadUsers)
</script>

<style scoped>
.users-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.gate-alert {
  border-radius: 12px;
}
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
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
}
</style>
