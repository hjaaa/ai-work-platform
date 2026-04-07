<template>
  <div class="project-list-page">
    <div class="page-header">
      <h2>我的项目</h2>
      <el-button type="primary" @click="showCreateDialog = true">
        <el-icon><Plus /></el-icon>
        新建项目
      </el-button>
    </div>

    <el-row :gutter="16" v-if="projects.length > 0">
      <el-col :span="8" v-for="project in projects" :key="project.projectId">
        <el-card class="project-card" shadow="hover">
          <template #header>
            <div class="card-header" @click="enterProject(project.projectId)">
              <span class="project-name">{{ project.name }}</span>
              <el-tag :type="statusTagType(project.status)" size="small">{{ project.status }}</el-tag>
            </div>
          </template>
          <p class="project-desc" @click="enterProject(project.projectId)">{{ project.description || '暂无描述' }}</p>
          <div class="project-footer">
            <span class="project-meta">{{ formatTime(project.updatedAt || project.createdAt) }}</span>
            <div class="project-actions" @click.stop>
              <el-button text size="small" @click="enterProject(project.projectId)">
                <el-icon><ChatDotRound /></el-icon> 对话
              </el-button>
              <el-button text size="small" @click="$router.push(`/project/${project.projectId}/detail`)">
                <el-icon><View /></el-icon> 详情
              </el-button>
              <el-popconfirm title="确认删除此项目？" @confirm="handleDelete(project.projectId)">
                <template #reference>
                  <el-button text size="small" type="danger">
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-else description="还没有项目，点击右上角新建一个吧" />

    <!-- 新建项目对话框 -->
    <el-dialog v-model="showCreateDialog" title="新建项目" width="500">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="项目名称">
          <el-input v-model="createForm.name" placeholder="例如：员工管理系统" />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input v-model="createForm.description" type="textarea" :rows="3"
                    placeholder="简单描述项目用途（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>

    <!-- 模式选择 -->
    <ModeSelector v-model="showModeSelector" @select="handleModeSelect" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ModeSelector from '../components/ModeSelector.vue'
import { listProjects, createProject, deleteProject } from '../api/project'
import { ElMessage } from 'element-plus'

const router = useRouter()
const projects = ref([])
const showCreateDialog = ref(false)
const showModeSelector = ref(false)
const newProjectId = ref('')
const creating = ref(false)
const createForm = ref({ name: '', description: '' })

onMounted(async () => {
  await loadProjects()
})

async function loadProjects() {
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } catch (e) {
    ElMessage.error('加载项目列表失败')
  }
}

async function handleCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  creating.value = true
  try {
    const res = await createProject(createForm.value.name, createForm.value.description)
    ElMessage.success('项目创建成功')
    showCreateDialog.value = false
    newProjectId.value = res.data.projectId
    createForm.value = { name: '', description: '' }
    showModeSelector.value = true
  } catch (e) {
    ElMessage.error('创建失败: ' + e.message)
  } finally {
    creating.value = false
  }
}

async function handleDelete(projectId) {
  try {
    await deleteProject(projectId)
    ElMessage.success('项目已删除')
    await loadProjects()
  } catch (e) {
    ElMessage.error('删除失败: ' + e.message)
  }
}

function handleModeSelect(mode) {
  router.push(`/project/${newProjectId.value}?mode=${mode}`)
}

function enterProject(projectId) {
  router.push(`/project/${projectId}`)
}

function statusTagType(status) {
  const map = { creating: 'info', active: '', deploying: 'warning', deployed: 'success', failed: 'danger' }
  return map[status] || 'info'
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}
</script>

<style scoped>
.project-list-page {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}
.project-card {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
}
.project-name {
  font-weight: bold;
}
.project-desc {
  color: #606266;
  font-size: 14px;
  margin: 0 0 12px 0;
  cursor: pointer;
}
.project-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.project-meta {
  color: #909399;
  font-size: 12px;
}
.project-actions {
  display: flex;
  gap: 4px;
}
</style>
