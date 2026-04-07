<template>
  <div class="project-detail-page">
    <div class="page-header">
      <el-button text @click="$router.push('/')">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <h2>{{ project?.name }}</h2>
      <el-button type="primary" @click="$router.push(`/project/${projectId}`)">继续对话</el-button>
    </div>

    <!-- 基本信息 -->
    <el-card class="info-card">
      <template #header>基本信息</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="项目 ID">{{ project?.projectId }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(project?.status)">{{ project?.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(project?.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ project?.description || '无' }}</el-descriptions-item>
        <el-descriptions-item label="访问地址">
          <a v-if="project?.deployUrl" :href="project.deployUrl" target="_blank">{{ project.deployUrl }}</a>
          <span v-else>未部署</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 生成历史 -->
    <el-card class="history-card">
      <template #header>生成历史</template>
      <el-table :data="generations" stripe v-if="generations.length > 0">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="row.type === 'prd' ? 'warning' : ''">{{ row.type === 'prd' ? 'PRD' : '代码' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="genStatusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ row.durationMs ? (row.durationMs / 1000).toFixed(1) + 's' : '-' }}</template>
        </el-table-column>
        <el-table-column label="错误信息">
          <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无生成记录" />
    </el-card>

    <!-- 部署历史 -->
    <el-card class="history-card">
      <template #header>部署历史</template>
      <el-table :data="deployments" stripe v-if="deployments.length > 0">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="deployStatusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="镜像版本" prop="imageTag" />
        <el-table-column label="目标服务器" prop="targetServer" />
        <el-table-column label="访问地址">
          <template #default="{ row }">
            <a v-if="row.deployUrl" :href="row.deployUrl" target="_blank">{{ row.deployUrl }}</a>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无部署记录" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getProject } from '../api/project'
import { listGenerations, listDeployments } from '../api/generation'

const route = useRoute()
const projectId = route.params.projectId

const project = ref(null)
const generations = ref([])
const deployments = ref([])

onMounted(async () => {
  const [projRes, genRes, deployRes] = await Promise.all([
    getProject(projectId),
    listGenerations(projectId),
    listDeployments(projectId)
  ])
  project.value = projRes.data
  generations.value = genRes.data || []
  deployments.value = deployRes.data || []
})

function statusTagType(status) {
  const map = { creating: 'info', active: '', deploying: 'warning', deployed: 'success', failed: 'danger' }
  return map[status] || 'info'
}
function genStatusType(status) {
  return { running: 'warning', success: 'success', failed: 'danger' }[status] || 'info'
}
function deployStatusType(status) {
  return { building: 'info', pushing: 'info', deploying: 'warning', running: 'success', failed: 'danger', rolled_back: 'warning' }[status] || 'info'
}
function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}
</script>

<style scoped>
.project-detail-page {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
  overflow-y: auto;
  height: 100%;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.page-header h2 {
  flex: 1;
  margin: 0;
}
.info-card, .history-card {
  margin-bottom: 16px;
}
</style>
