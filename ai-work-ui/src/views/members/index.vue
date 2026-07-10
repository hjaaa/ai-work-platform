<template>
  <div class="members">
    <div class="page-head">
      <div class="page-head-text">
        <div class="page-title">成员管理</div>
        <div class="page-sub">管理团队成员、部门归属与访问角色</div>
      </div>
      <el-button type="primary" @click="openInvite">＋ 邀请成员</el-button>
    </div>

    <div class="dc-card search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="姓名 / 邮箱">
          <el-input
            v-model="query.keyword"
            placeholder="搜索关键字"
            clearable
            style="width: 200px"
          />
        </el-form-item>
        <el-form-item label="部门">
          <el-select
            v-model="query.dept"
            placeholder="全部部门"
            clearable
            style="width: 150px"
          >
            <el-option v-for="dept in depts" :key="dept" :label="dept" :value="dept" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="query.role"
            placeholder="全部角色"
            clearable
            style="width: 130px"
          >
            <el-option v-for="role in roles" :key="role" :label="role" :value="role" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            clearable
            style="width: 120px"
          >
            <el-option label="活跃" value="active" />
            <el-option label="待接受" value="invited" />
            <el-option label="已停用" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onQuery">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="dc-card">
      <div class="table-head">
        <div class="table-head-left">
          <span class="table-title">成员列表</span>
          <el-tag type="info" disable-transitions>共 {{ members.length }} 人</el-tag>
        </div>
        <el-button @click="openInvite">＋ 邀请成员</el-button>
      </div>

      <el-table :data="members" style="width: 100%">
        <el-table-column label="成员" min-width="240">
          <template #default="{ row }">
            <div class="member-cell">
              <div class="member-av" :style="{ background: row.color }">{{ row.av }}</div>
              <div class="member-meta">
                <span class="member-name">{{ row.name }}</span>
                <span class="member-email">{{ row.email }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="dept" label="部门" width="132" />
        <el-table-column label="角色" width="108">
          <template #default="{ row }">
            <el-tag type="info" disable-transitions>{{ row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="116">
          <template #default="{ row }">
            <el-tag :type="getStatusMeta(row.status).type" disable-transitions>
              {{ getStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="last" label="最近活跃" width="128" />
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onRemove(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="members.length"
          :page-size="10"
          :current-page="1"
        />
      </div>
    </div>

    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="480px">
      <el-form :model="formData" label-position="top">
        <el-form-item label="姓名">
          <el-input v-model="formData.name" placeholder="请输入成员姓名" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="formData.email" placeholder="name@ai-platform.com" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="部门" class="form-col">
            <el-select v-model="formData.dept" placeholder="选择部门" style="width: 100%">
              <el-option v-for="dept in depts" :key="dept" :label="dept" :value="dept" />
            </el-select>
          </el-form-item>
          <el-form-item label="角色" class="form-col">
            <el-select v-model="formData.role" placeholder="选择角色" style="width: 100%">
              <el-option v-for="role in roles" :key="role" :label="role" :value="role" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MEMBERS, STATUS_MAP, type Member, type MemberStatus } from './mock'

interface MemberQuery {
  keyword: string
  dept: string
  role: string
  status: '' | MemberStatus
}

interface MemberForm {
  name: string
  email: string
  dept: string
  role: string
}

const members = ref<Member[]>([...MEMBERS])
const depts = ['算法平台', '数据工程', '产品设计', '运营支持']
const roles = ['管理员', '开发者', '编辑者', '只读']

const query = reactive<MemberQuery>({
  keyword: '',
  dept: '',
  role: '',
  status: '',
})

function onQuery() {
  ElMessage.info('示例页：查询为占位交互')
}

function onReset() {
  query.keyword = ''
  query.dept = ''
  query.role = ''
  query.status = ''
}

function getStatusMeta(status: MemberStatus) {
  return STATUS_MAP[status]
}

const dialogOpen = ref(false)
const dialogTitle = ref('邀请成员')
const formData = reactive<MemberForm>({
  name: '',
  email: '',
  dept: '',
  role: '',
})

function resetForm() {
  formData.name = ''
  formData.email = ''
  formData.dept = ''
  formData.role = ''
}

function openInvite() {
  dialogTitle.value = '邀请成员'
  resetForm()
  dialogOpen.value = true
}

function openEdit(row: Member) {
  dialogTitle.value = '编辑成员'
  formData.name = row.name
  formData.email = row.email
  formData.dept = row.dept
  formData.role = row.role
  dialogOpen.value = true
}

function onSubmit() {
  dialogOpen.value = false
  ElMessage.success('示例页：已保存（占位）')
}

function onRemove(row: Member) {
  ElMessageBox.confirm(`确定移除成员「${row.name}」吗？`, '移除成员', {
    type: 'warning',
    confirmButtonText: '移除',
    cancelButtonText: '取消',
  })
    .then(() => {
      ElMessage.success('示例页：已移除（占位）')
    })
    .catch(() => {})
}
</script>

<style scoped>
.members {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.page-head-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.page-title {
  font-size: 20px;
  font-weight: 500;
  line-height: 1.4;
  color: var(--dc-ink);
}

.page-sub {
  font-size: 14px;
  color: var(--dc-ink-subtle);
}

.search-card {
  padding: 16px 20px;
}

.search-card :deep(.el-form-item) {
  margin-bottom: 0;
}

.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px 8px;
}

.table-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.table-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--dc-ink);
}

.member-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.member-av {
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 9999px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.member-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.member-name {
  font-size: 14px;
  color: var(--dc-ink);
}

.member-email {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
}

.form-row {
  display: flex;
  gap: 16px;
}

.form-col {
  flex: 1;
}
</style>
