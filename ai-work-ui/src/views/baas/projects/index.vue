<template>
  <div class="baas-projects">
    <div class="page-head">
      <div>
        <div class="page-title">BaaS 项目</div>
        <div class="page-sub">创建项目后自动获得数据 CRUD API 与终端用户认证能力</div>
      </div>
      <el-button type="primary" @click="openCreate">＋ 新建项目</el-button>
    </div>

    <div class="dc-card search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item>
          <el-input v-model="query.keyword" placeholder="搜索名称 / ref" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 160px">
            <el-option
              v-for="(meta, status) in PROJECT_STATUS_MAP"
              :key="status"
              :label="meta.label"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="loadProjects">刷新</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="dc-card">
      <div class="table-head">
        <div class="table-title">项目列表</div>
        <el-tag type="info" disable-transitions>共 {{ filtered.length }} 个</el-tag>
      </div>
      <el-table v-loading="loading" :data="filtered" style="width: 100%" @row-click="goDetail">
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="projectRef" label="ref" min-width="200" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="PROJECT_STATUS_MAP[row.status as ProjectStatus].type" disable-transitions>
              {{ PROJECT_STATUS_MAP[row.status as ProjectStatus].label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createOpen" title="新建项目" width="480px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="项目名称">
          <el-input v-model="createName" maxlength="64" placeholder="如:my-blog" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 明文 key 仅此一次 -->
    <el-dialog v-model="keysOpen" title="API Key(仅显示一次)" width="560px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon title="请立即保存以下密钥,关闭后将无法再次查看明文" />
      <div v-if="createdKeys" class="key-rows">
        <div class="key-row">
          <span class="key-label">publishable</span>
          <code class="key-value">{{ createdKeys.publishableKey }}</code>
          <el-button link type="primary" @click="copyText(createdKeys.publishableKey)">复制</el-button>
        </div>
        <div class="key-row">
          <span class="key-label">secret</span>
          <code class="key-value">{{ createdKeys.secretKey }}</code>
          <el-button link type="primary" @click="copyText(createdKeys.secretKey)">复制</el-button>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="closeKeys">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createProject, listProjects } from '@/api/baas/project'
import type { CreatedProjectVO, ProjectStatus, ProjectVO } from '@/api/baas/types'
import { PROJECT_STATUS_MAP } from './statusMaps'
import { copyText } from './clipboard'

const router = useRouter()
const projects = ref<ProjectVO[]>([])
const loading = ref(false)
const query = reactive<{ keyword: string; status: '' | ProjectStatus }>({ keyword: '', status: '' })

const filtered = computed(() =>
  projects.value.filter((p) => {
    const kw = query.keyword.trim().toLowerCase()
    const hitKw = !kw || p.name.toLowerCase().includes(kw) || p.projectRef.toLowerCase().includes(kw)
    const hitStatus = !query.status || p.status === query.status
    return hitKw && hitStatus
  }),
)

async function loadProjects() {
  loading.value = true
  try {
    const res = await listProjects()
    projects.value = res.data ?? []
  } finally {
    loading.value = false
  }
}

function goDetail(row: ProjectVO) {
  router.push(`/baas/projects/${row.projectRef}`)
}

const createOpen = ref(false)
const createName = ref('')
const creating = ref(false)
const keysOpen = ref(false)
const createdKeys = ref<CreatedProjectVO | null>(null)

function openCreate() {
  createName.value = ''
  createOpen.value = true
}

async function onCreate() {
  const name = createName.value.trim()
  if (!name) {
    ElMessage.warning('请输入项目名称')
    return
  }
  creating.value = true
  try {
    const res = await createProject(name)
    createOpen.value = false
    createdKeys.value = res.data
    keysOpen.value = true
    await loadProjects()
  } finally {
    creating.value = false
  }
}

function closeKeys() {
  keysOpen.value = false
  createdKeys.value = null // 明文只存活于弹窗生命周期
}

onMounted(loadProjects)
</script>

<style scoped>
.baas-projects {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
}
.page-title {
  font-size: 20px;
  font-weight: 500;
  color: var(--dc-ink);
}
.page-sub {
  font-size: 14px;
  color: var(--dc-ink-subtle);
  margin-top: 4px;
}
.search-card {
  padding: 16px 20px;
}
.search-card :deep(.el-form-item) {
  margin-bottom: 0;
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
.key-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 16px;
}
.key-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.key-label {
  width: 90px;
  font-size: 14px;
  color: var(--dc-ink-muted);
}
.key-value {
  flex: 1;
  font-size: 12px;
  word-break: break-all;
  background: var(--dc-fill-1);
  border-radius: 6px;
  padding: 8px 10px;
}
</style>
