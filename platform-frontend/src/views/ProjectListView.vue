<template>
  <div class="project-list-page">
    <!-- 顶部操作栏：只保留新建项目按钮 -->
    <div class="page-header">
      <button class="btn-create" @click="openCreateDialog">
        <svg class="btn-create-icon" width="20" height="20" viewBox="0 0 1024 1024">
          <path d="M115.2 512a396.8 396.8 0 1 1 793.6 0 396.8 396.8 0 0 1-793.6 0zM512 698.88a33.28 33.28 0 0 0 33.28-33.28v-120.32H665.6a33.28 33.28 0 0 0 0-66.56h-120.32V358.4a33.28 33.28 0 0 0-66.56 0v120.32H358.4a33.28 33.28 0 0 0 0 66.56h120.32V665.6c0 18.3808 14.8992 33.28 33.28 33.28z" fill="#fff"/>
        </svg>
        新建项目
      </button>
    </div>

    <!-- 项目卡片网格 -->
    <div class="project-grid">
      <div
        v-for="project in projects"
        :key="project.projectId"
        class="project-card"
        @click="openEditDialog(project)"
      >
        <div class="card-cover" :style="{ backgroundColor: getCoverColor(project.projectId) }">
          <!-- clone 中：显示加载动画（仅 git 项目） -->
          <div v-if="project.status === 'creating' && project.projectType !== 'local'" class="clone-loading">
            <svg class="clone-spinner" width="24" height="24" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="2"/>
              <path d="M12 2a10 10 0 0 1 10 10" stroke="#fff" stroke-width="2" stroke-linecap="round"/>
            </svg>
            <span class="clone-text">代码拉取中...</span>
          </div>
          <!-- clone 失败：显示错误标记（仅 git 项目） -->
          <div v-else-if="project.status === 'failed' && project.projectType !== 'local'" class="clone-failed">
            <span class="failed-icon">!</span>
            <span class="clone-text">拉取失败</span>
          </div>
          <!-- 正常状态 -->
          <span v-else class="cover-initial">{{ project.name?.charAt(0) || 'P' }}</span>
          <!-- 本地项目标签 -->
          <span v-if="project.projectType === 'local'" class="card-type-tag tag-local">本地</span>
          <span v-else class="card-type-tag tag-git">Git</span>
        </div>
        <div class="card-info">
          <span class="card-name">{{ project.name }}</span>
          <span v-if="project.status === 'failed' && project.projectType !== 'local'" class="card-error" :title="project.cloneErrorMessage">
            {{ project.cloneErrorMessage?.substring(0, 30) }}...
          </span>
        </div>
        <!-- 悬浮操作按钮 -->
        <div class="card-actions" @click.stop>
          <!-- 重试拉取：仅 git 失败项目显示 -->
          <span
            v-if="project.status === 'failed' && project.projectType !== 'local'"
            class="action-btn action-retry"
            title="重试拉取代码"
            @click="handleRetryClone(project.projectId)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="23 4 23 10 17 10"/>
              <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
            </svg>
          </span>
          <el-popconfirm title="确认删除此项目？" @confirm="handleDelete(project.projectId)">
            <template #reference>
              <span class="action-btn action-delete" title="删除">
                <el-icon :size="14"><Delete /></el-icon>
              </span>
            </template>
          </el-popconfirm>
        </div>
      </div>

      <!-- 创建项目占位卡片 -->
      <div class="project-card create-card" @click="openCreateDialog">
        <div class="create-card-body">
          <span class="create-icon">+</span>
          <span class="create-text">创建项目</span>
        </div>
      </div>
    </div>

    <!-- 新建项目对话框 - Teambition 风格 -->
    <Teleport to="body">
      <div v-if="showDialog" class="tb-overlay" @click.self="showDialog = false">
        <div class="tb-dialog">
          <!-- Header -->
          <div class="tb-dialog-header">
            <span class="tb-dialog-title">{{ dialogMode === 'create' ? '新建项目' : '编辑项目' }}</span>
            <button class="tb-dialog-close" @click="showDialog = false">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M1 1l10 10M11 1L1 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <!-- Body -->
          <div class="tb-dialog-body">
            <div class="tb-form">
              <!-- 项目类型（仅创建时显示） -->
              <div v-if="dialogMode === 'create'" class="tb-form-item">
                <label class="tb-label">项目类型</label>
                <div class="tb-type-switch">
                  <button
                    class="tb-type-btn"
                    :class="{ active: formData.projectType === 'git' }"
                    @click="formData.projectType = 'git'"
                  >
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <circle cx="12" cy="12" r="4"/><line x1="1.05" y1="12" x2="7" y2="12"/><line x1="17.01" y1="12" x2="22.96" y2="12"/>
                    </svg>
                    Git 仓库
                  </button>
                  <button
                    class="tb-type-btn"
                    :class="{ active: formData.projectType === 'local' }"
                    @click="formData.projectType = 'local'"
                  >
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
                    </svg>
                    本地项目
                  </button>
                </div>
              </div>
              <!-- 项目名称（必填） -->
              <div class="tb-form-item">
                <label class="tb-label">项目名称 <span class="tb-required">*</span></label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'name' }">
                  <input
                    v-model="formData.name"
                    class="tb-input"
                    placeholder="例如：员工管理系统"
                    @focus="focusedField = 'name'"
                    @blur="focusedField = ''"
                  />
                </div>
              </div>
              <!-- Git 仓库地址（git 类型必填） -->
              <div v-if="currentProjectType === 'git'" class="tb-form-item">
                <label class="tb-label">Git 仓库地址 <span class="tb-required">*</span></label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'gitUrl' }">
                  <input
                    v-model="formData.gitUrl"
                    class="tb-input"
                    placeholder="https://github.com/user/repo.git"
                    @focus="focusedField = 'gitUrl'"
                    @blur="focusedField = ''"
                  />
                </div>
              </div>
              <!-- 默认分支（git 类型选填） -->
              <div v-if="currentProjectType === 'git'" class="tb-form-item">
                <label class="tb-label">默认分支</label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'branch' }">
                  <input
                    v-model="formData.defaultBranch"
                    class="tb-input"
                    placeholder="main"
                    @focus="focusedField = 'branch'"
                    @blur="focusedField = ''"
                  />
                </div>
              </div>
              <!-- 本地项目路径（local 类型必填） -->
              <div v-if="currentProjectType === 'local'" class="tb-form-item">
                <label class="tb-label">项目路径 <span class="tb-required">*</span></label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'localPath' }">
                  <input
                    v-model="formData.localPath"
                    class="tb-input"
                    :placeholder="localPathPlaceholder"
                    @focus="focusedField = 'localPath'"
                    @blur="focusedField = ''"
                  />
                </div>
                <span class="tb-hint">请输入项目在本机的绝对路径</span>
              </div>
              <!-- 项目描述（选填） -->
              <div class="tb-form-item">
                <label class="tb-label">项目描述</label>
                <div class="tb-textarea-wrap" :class="{ focused: focusedField === 'desc' }">
                  <textarea
                    v-model="formData.description"
                    class="tb-textarea"
                    placeholder="简单描述项目用途，帮助 AI 更好地理解代码上下文"
                    rows="3"
                    @focus="focusedField = 'desc'"
                    @blur="focusedField = ''"
                  ></textarea>
                </div>
              </div>
            </div>
          </div>
          <!-- Footer -->
          <div class="tb-dialog-footer">
            <button class="tb-btn tb-btn-cancel" @click="showDialog = false">取消</button>
            <button class="tb-btn tb-btn-primary" @click="handleSubmit" :disabled="submitting">
              {{ submitting ? '提交中...' : '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 模式选择 -->
    <ModeSelector v-model="showModeSelector" @select="handleModeSelect" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import ModeSelector from '../components/ModeSelector.vue'
import { listProjects, createProject, updateProject, deleteProject, retryClone } from '../api/project'
import { connectWebSocket, disconnectWebSocket } from '../api/websocket'
import { ElMessage } from 'element-plus'

const router = useRouter()
const projects = ref([])
const showDialog = ref(false)
const dialogMode = ref('create') // 'create' | 'edit'
const editingProjectId = ref('')
const showModeSelector = ref(false)
const newProjectId = ref('')
const submitting = ref(false)
const formData = ref({ name: '', projectType: 'git', gitUrl: '', defaultBranch: '', localPath: '', description: '' })
const focusedField = ref('')
const editingProjectType = ref('git') // 编辑时记录原始项目类型

// 当前项目类型：创建时用 formData，编辑时用原始类型（不可切换）
const currentProjectType = computed(() => {
  if (dialogMode.value === 'edit') return editingProjectType.value
  return formData.value.projectType
})

// 根据操作系统显示不同的路径提示
const localPathPlaceholder = computed(() => {
  const isWindows = navigator.platform?.toLowerCase().includes('win')
  return isWindows ? 'D:\\projects\\my-project' : '/Users/username/projects/my-project'
})

// 根据项目 ID 生成封面背景色
const coverColors = [
  '#4A90D9', '#7B68EE', '#E8725C', '#50C878',
  '#F5A623', '#8B5CF6', '#06B6D4', '#EC4899',
]
function getCoverColor(projectId) {
  if (!projectId) return coverColors[0]
  let hash = 0
  for (let i = 0; i < projectId.length; i++) {
    hash = projectId.charCodeAt(i) + ((hash << 5) - hash)
  }
  return coverColors[Math.abs(hash) % coverColors.length]
}

// 正在监听 clone 进度的项目 ID 集合
const watchingProjects = ref(new Set())

onMounted(async () => {
  await loadProjects()
  // 对所有 creating 状态的项目启动 WebSocket 监听
  projects.value
    .filter(p => p.status === 'creating')
    .forEach(p => watchCloneProgress(p.projectId))
})

onUnmounted(() => {
  disconnectWebSocket()
})

function watchCloneProgress(projectId) {
  if (watchingProjects.value.has(projectId)) return
  watchingProjects.value.add(projectId)

  connectWebSocket(projectId, async (msg) => {
    // 收到消息后刷新项目列表
    await loadProjects()
    // 如果 clone 完成或失败，停止监听
    const project = projects.value.find(p => p.projectId === projectId)
    if (project && project.status !== 'creating') {
      watchingProjects.value.delete(projectId)
      if (project.status === 'active') {
        ElMessage.success(`项目 "${project.name}" 代码拉取完成`)
      } else if (project.status === 'failed') {
        ElMessage.error(`项目 "${project.name}" 代码拉取失败`)
      }
    }
  })
}

async function loadProjects() {
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } catch (e) {
    ElMessage.error('加载项目列表失败')
  }
}

function openCreateDialog() {
  dialogMode.value = 'create'
  editingProjectId.value = ''
  formData.value = { name: '', projectType: 'git', gitUrl: '', defaultBranch: '', localPath: '', description: '' }
  showDialog.value = true
}

function openEditDialog(project) {
  dialogMode.value = 'edit'
  editingProjectId.value = project.projectId
  editingProjectType.value = project.projectType || 'git'
  formData.value = {
    name: project.name || '',
    projectType: project.projectType || 'git',
    gitUrl: project.gitUrl || '',
    defaultBranch: project.defaultBranch || '',
    localPath: project.localPath || '',
    description: project.description || '',
  }
  showDialog.value = true
}

async function handleSubmit() {
  if (!formData.value.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  const type = currentProjectType.value
  if (type === 'git' && !formData.value.gitUrl.trim()) {
    ElMessage.warning('请输入 Git 仓库地址')
    return
  }
  if (type === 'local' && !formData.value.localPath.trim()) {
    ElMessage.warning('请输入项目路径')
    return
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      const res = await createProject(formData.value)
      if (type === 'local') {
        ElMessage.success('本地项目创建成功')
      } else {
        ElMessage.success('项目创建成功，正在拉取代码...')
        watchCloneProgress(res.data.projectId)
      }
      newProjectId.value = res.data.projectId
      await loadProjects()
    } else {
      await updateProject(editingProjectId.value, formData.value)
      ElMessage.success('项目更新成功')
      await loadProjects()
    }
    showDialog.value = false
  } catch (e) {
    ElMessage.error((dialogMode.value === 'create' ? '创建' : '更新') + '失败: ' + e.message)
  } finally {
    submitting.value = false
  }
}

async function handleRetryClone(projectId) {
  try {
    await retryClone(projectId)
    ElMessage.success('正在重新拉取代码...')
    await loadProjects()
    watchCloneProgress(projectId)
  } catch (e) {
    ElMessage.error('重试失败: ' + e.message)
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
  min-height: 100%;
  background: transparent;
  font-family: -apple-system, system-ui, "Segoe UI", Roboto, "Helvetica Neue",
               "PingFang SC", "Noto Sans", "Noto Sans CJK SC",
               "Microsoft YaHei", sans-serif;
}

/* 顶部操作栏 */
.page-header {
  margin-bottom: 16px;
}

.btn-create {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 16px;
  background: #0091FF;
  color: #FFFFFF;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 400;
  cursor: pointer;
  transition: background 0.2s;
}
.btn-create:hover {
  background: #0082E5;
}
.btn-create:active {
  background: #0074CC;
}
.btn-create-icon {
  flex-shrink: 0;
}

/* 卡片网格 */
.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 200px));
  gap: 16px;
}

/* 项目卡片 */
.project-card {
  position: relative;
  border-radius: 12px;
  background: #FFFFFF;
  box-shadow: rgba(38, 38, 38, 0.1) 0px 1px 5px 0px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  overflow: hidden;
}
.project-card:hover {
  box-shadow: rgba(38, 38, 38, 0.15) 0px 2px 8px 0px;
  transform: translateY(-2px);
}

/* 封面区域 */
.card-cover {
  position: relative;
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.cover-initial {
  font-size: 36px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
  text-transform: uppercase;
  user-select: none;
}

/* 卡片信息 */
.card-info {
  padding: 12px;
}
.card-name {
  font-size: 14px;
  color: #262626;
  font-weight: 400;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-error {
  font-size: 12px;
  color: #F56C6C;
  display: block;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Clone 加载状态 */
.clone-loading,
.clone-failed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.clone-text {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
}
.clone-spinner {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.failed-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
}

/* 悬浮操作 */
.card-actions {
  position: absolute;
  top: 8px;
  right: 8px;
  display: none;
}
.project-card:hover .card-actions {
  display: flex;
  gap: 4px;
}
.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.9);
  color: #262626;
  cursor: pointer;
  transition: background 0.15s;
}
.action-btn:hover {
  background: #FFFFFF;
}
.action-retry:hover {
  color: #0091FF;
}
.action-delete:hover {
  color: #F56C6C;
}

/* 项目类型标签 */
.card-type-tag {
  position: absolute;
  top: 8px;
  left: 8px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
  user-select: none;
}
.tag-local {
  background: rgba(255, 255, 255, 0.85);
  color: #50C878;
}
.tag-git {
  background: rgba(255, 255, 255, 0.85);
  color: #4A90D9;
}

/* 创建项目占位卡片 */
.create-card {
  box-shadow: none;
  border: 1px dashed rgba(38, 38, 38, 0.15);
  background: #FFFFFF;
}
.create-card:hover {
  box-shadow: none;
  border-color: rgba(38, 38, 38, 0.25);
  transform: none;
}
.create-card-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 140px; /* 与项目卡片总高度一致：100px 封面 + 40px 信息区 */
  gap: 8px;
}
.create-icon {
  font-size: 28px;
  color: rgba(38, 38, 38, 0.36);
  line-height: 1;
}
.create-text {
  font-size: 14px;
  color: rgba(38, 38, 38, 0.36);
}
</style>

<!-- Teambition 弹窗样式（Teleport 到 body，不能 scoped） -->
<style>
/* 遮罩层 */
.tb-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 弹窗容器 - 固定高度，footer 沉底 */
.tb-dialog {
  width: 560px;
  height: 789px;
  max-height: 90vh;
  background: #fff;
  border-radius: 12px;
  box-shadow:
    rgba(31, 34, 37, 0.08) 0px 0px 0px 1px,
    rgba(0, 0, 0, 0.04) 0px 8px 16px -2px,
    rgba(0, 0, 0, 0.04) 0px 2px 8px 0px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: -apple-system, system-ui, "Segoe UI", Roboto, "Helvetica Neue",
               "PingFang SC", "Noto Sans", "Noto Sans CJK SC",
               "Microsoft YaHei", sans-serif;
}

/* Header */
.tb-dialog-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 20px 12px;
  flex-shrink: 0;
}

.tb-dialog-title {
  font-size: 16px;
  font-weight: 500;
  color: #262626;
}

.tb-dialog-close {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: none;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.36);
  border-radius: 4px;
  transition: color 0.2s, background 0.2s;
}
.tb-dialog-close:hover {
  color: #262626;
  background: rgba(38, 38, 38, 0.06);
}

/* Body */
.tb-dialog-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 20px 0;
}

/* Form */
.tb-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tb-form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tb-label {
  font-size: 14px;
  font-weight: 500;
  color: #262626;
  line-height: 20px;
}

.tb-required {
  color: #F5222D;
  margin-left: 2px;
}

.tb-hint {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  margin-top: 2px;
}

/* 项目类型切换按钮组 */
.tb-type-switch {
  display: flex;
  gap: 8px;
}
.tb-type-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  border: 1px solid rgba(38, 38, 38, 0.22);
  border-radius: 8px;
  background: transparent;
  font-size: 14px;
  color: rgba(38, 38, 38, 0.76);
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s, background 0.2s;
  font-family: inherit;
}
.tb-type-btn:hover {
  border-color: rgba(38, 38, 38, 0.36);
}
.tb-type-btn.active {
  border-color: #0091FF;
  color: #0091FF;
  background: rgba(0, 145, 255, 0.04);
}

/* 输入框 */
.tb-input-wrap {
  display: flex;
  align-items: center;
  height: 36px;
  border: 1px solid rgba(38, 38, 38, 0.22);
  border-radius: 8px;
  background: transparent;
  transition: border-color 0.2s;
  padding: 0 8px;
}
.tb-input-wrap:hover {
  border-color: rgba(38, 38, 38, 0.36);
}
.tb-input-wrap.focused {
  border-color: #0091FF;
}

.tb-input {
  flex: 1;
  height: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: #262626;
  font-family: inherit;
}
.tb-input::placeholder {
  color: rgba(38, 38, 38, 0.36);
}

.tb-input-suffix {
  flex-shrink: 0;
  color: rgba(38, 38, 38, 0.36);
  margin-left: 4px;
}

/* 多行文本框 */
.tb-textarea-wrap {
  border: 1px solid rgba(38, 38, 38, 0.22);
  border-radius: 8px;
  background: transparent;
  transition: border-color 0.2s;
  padding: 8px;
}
.tb-textarea-wrap:hover {
  border-color: rgba(38, 38, 38, 0.36);
}
.tb-textarea-wrap.focused {
  border-color: #0091FF;
}

.tb-textarea {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: #262626;
  font-family: inherit;
  resize: vertical;
  line-height: 1.5;
}
.tb-textarea::placeholder {
  color: rgba(38, 38, 38, 0.36);
}

/* 日期范围选择器 */
.tb-date-range {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tb-date-picker {
  height: 32px;
  padding: 0 6px;
  display: flex;
  align-items: center;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.2s;
}
.tb-date-picker:hover {
  background: rgba(38, 38, 38, 0.06);
}

.tb-date-placeholder {
  font-size: 14px;
  color: rgba(38, 38, 38, 0.6);
  white-space: nowrap;
}

.tb-date-sep {
  color: rgba(38, 38, 38, 0.36);
  font-size: 14px;
}

/* Footer */
.tb-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 0 20px 16px;
  flex-shrink: 0;
  margin-top: auto;
}

.tb-btn {
  height: 36px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 400;
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
  font-family: inherit;
}

.tb-btn-cancel {
  background: transparent;
  color: #262626;
  border: 1px solid rgba(0, 145, 255, 0.16);
}
.tb-btn-cancel:hover {
  border-color: rgba(0, 145, 255, 0.36);
  background: rgba(0, 145, 255, 0.04);
}

.tb-btn-primary {
  background: #0091FF;
  color: #fff;
  border: 1px solid transparent;
}
.tb-btn-primary:hover {
  background: #0082E5;
}
.tb-btn-primary:active {
  background: #0074CC;
}
.tb-btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
