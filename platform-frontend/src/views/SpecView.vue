<template>
  <div class="spec-view">
    <!-- 页头 -->
    <div class="spec-header">
      <h2 class="spec-title">开发规范</h2>
      <button class="btn-create" @click="openCreateDialog">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        新建规范
      </button>
    </div>

    <!-- 类型过滤 tab -->
    <div class="spec-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        class="spec-tab"
        :class="{ active: activeTab === tab.value }"
        @click="switchTab(tab.value)"
      >{{ tab.label }}</button>
    </div>

    <!-- 规范列表 -->
    <div class="spec-list">
      <div v-if="loading" class="spec-empty">加载中...</div>
      <div v-else-if="filteredSpecs.length === 0" class="spec-empty">
        暂无规范，点击「新建规范」添加
      </div>
      <div
        v-else
        v-for="spec in filteredSpecs"
        :key="spec.specId"
        class="spec-card"
      >
        <div class="spec-card-main">
          <div class="spec-card-top">
            <span class="spec-name">{{ spec.name }}</span>
            <span class="spec-type-tag" :class="typeTagClass(spec.specType)">
              {{ typeLabel(spec.specType) }}
            </span>
          </div>
          <p class="spec-preview">{{ trimContent(spec.content) }}</p>
        </div>
        <div class="spec-card-actions">
          <button class="action-btn action-link" title="关联项目" @click="openLinkDialog(spec)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>
              <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>
            </svg>
          </button>
          <button class="action-btn action-edit" title="编辑" @click="openEditDialog(spec)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
            </svg>
          </button>
          <el-popconfirm title="确认删除此规范？有关联项目时需先解除关联。" @confirm="handleDelete(spec.specId)">
            <template #reference>
              <button class="action-btn action-delete" title="删除">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="3 6 5 6 21 6"/>
                  <path d="M19 6l-1 14H6L5 6"/>
                  <path d="M10 11v6m4-6v6"/>
                  <path d="M9 6V4h6v2"/>
                </svg>
              </button>
            </template>
          </el-popconfirm>
        </div>
      </div>
    </div>

    <!-- 新建/编辑弹窗 -->
    <Teleport to="body">
      <div v-if="showDialog" class="sp-overlay">
        <div class="sp-dialog">
          <div class="sp-dialog-header">
            <span class="sp-dialog-title">{{ editingSpec ? '编辑规范' : '新建规范' }}</span>
            <button class="sp-dialog-close" @click="closeDialog">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M1 1l10 10M11 1L1 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <div class="sp-dialog-body">
            <div class="sp-form">
              <!-- 名称 -->
              <div class="sp-form-item">
                <label class="sp-label">名称 <span class="sp-required">*</span></label>
                <input
                  v-model="form.name"
                  class="sp-input"
                  :class="{ error: formErrors.name }"
                  placeholder="规范名称"
                  maxlength="100"
                />
                <span v-if="formErrors.name" class="sp-error">{{ formErrors.name }}</span>
              </div>

              <!-- 类型 -->
              <div class="sp-form-item">
                <label class="sp-label">规范类型 <span class="sp-required">*</span></label>
                <div class="sp-type-switch">
                  <button
                    v-for="t in typeOptions"
                    :key="t.value"
                    class="sp-type-btn"
                    :class="{ active: form.specType === t.value }"
                    @click="form.specType = t.value"
                  >{{ t.label }}</button>
                </div>
              </div>

              <!-- 内容 -->
              <div class="sp-form-item">
                <label class="sp-label">规范内容（Markdown）</label>
                <textarea
                  v-model="form.content"
                  class="sp-textarea"
                  placeholder="输入规范内容，支持 Markdown 格式..."
                  rows="12"
                ></textarea>
              </div>
            </div>
          </div>
          <div class="sp-dialog-footer">
            <button class="sp-btn-cancel" @click="closeDialog">取消</button>
            <button class="sp-btn-confirm" :disabled="submitting" @click="handleSubmit">
              {{ submitting ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 关联项目弹窗 -->
    <Teleport to="body">
      <div v-if="showLinkDialog" class="sp-overlay" @click.self="closeLinkDialog">
        <div class="sp-dialog sp-dialog-link">
          <div class="sp-dialog-header">
            <span class="sp-dialog-title">关联项目 — {{ linkingSpec?.name }}</span>
            <button class="sp-dialog-close" @click="closeLinkDialog">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M1 1l10 10M11 1L1 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <div class="sp-dialog-body">
            <!-- 已关联项目 -->
            <div v-if="linkedProjects.length > 0" class="sp-link-section">
              <p class="sp-section-label">已关联项目</p>
              <div v-for="lp in linkedProjects" :key="lp.projectId" class="sp-link-row">
                <span class="sp-link-project-name">{{ getProjectName(lp.projectId) }}</span>
                <button class="sp-unlink-btn" :disabled="unlinking === lp.projectId" @click="handleUnlink(lp.projectId)">
                  {{ unlinking === lp.projectId ? '解除中...' : '解除关联' }}
                </button>
              </div>
            </div>
            <div v-else class="sp-link-empty">暂未关联任何项目</div>

            <!-- 关联新项目 -->
            <div class="sp-link-section">
              <p class="sp-section-label">关联到项目</p>
              <div class="sp-link-add">
                <select v-model="selectedProjectId" class="sp-select">
                  <option value="">请选择项目</option>
                  <option
                    v-for="project in availableProjects"
                    :key="project.projectId"
                    :value="project.projectId"
                  >{{ project.name }}</option>
                </select>
                <button class="sp-link-confirm-btn" :disabled="!selectedProjectId || linking" @click="handleLink">
                  {{ linking ? '关联中...' : '确认关联' }}
                </button>
              </div>
              <p v-if="availableProjects.length === 0" class="sp-hint">暂无可关联的项目（需要项目完成初始化且 codePath 不为空）</p>
            </div>
          </div>
          <div class="sp-dialog-footer">
            <button class="sp-btn-cancel" @click="closeLinkDialog">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listSpecs, createSpec, updateSpec, deleteSpec, listSpecLinks, linkSpecToProject, unlinkSpecFromProject } from '../api/spec'
import { listProjects } from '../api/project'

const tabs = [
  { label: '全部', value: '' },
  { label: 'UI', value: 'UI' },
  { label: '前端', value: 'FRONTEND' },
  { label: '后端', value: 'BACKEND' },
]

const typeOptions = [
  { label: 'UI 设计', value: 'UI' },
  { label: '前端开发', value: 'FRONTEND' },
  { label: '后端开发', value: 'BACKEND' },
]

const activeTab = ref('')
const specs = ref([])
const projects = ref([])
const loading = ref(false)

// 新建/编辑
const showDialog = ref(false)
const submitting = ref(false)
const editingSpec = ref(null)
const form = ref({ name: '', specType: 'BACKEND', content: '' })
const formErrors = ref({})

// 关联
const showLinkDialog = ref(false)
const linkingSpec = ref(null)
const linkedProjects = ref([])
const selectedProjectId = ref('')
const linking = ref(false)
const unlinking = ref('')

const filteredSpecs = computed(() => {
  if (!activeTab.value) return specs.value
  return specs.value.filter(s => s.specType === activeTab.value)
})

/** 过滤掉已关联的项目，且要求 codePath 不为空 */
const availableProjects = computed(() => {
  const linkedIds = new Set(linkedProjects.value.map(lp => lp.projectId))
  return projects.value.filter(p => !linkedIds.has(p.projectId) && (p.codePath || p.localPath))
})

onMounted(async () => {
  await Promise.all([loadSpecs(), loadProjects()])
})

async function loadSpecs() {
  loading.value = true
  try {
    const res = await listSpecs()
    specs.value = res.data || []
  } catch (e) {
    ElMessage.error('加载规范列表失败')
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } catch (e) {
    // 静默忽略
  }
}

function switchTab(value) {
  activeTab.value = value
}

function typeLabel(type) {
  const map = { UI: 'UI', FRONTEND: '前端', BACKEND: '后端' }
  return map[type] || type
}

function typeTagClass(type) {
  return { 'tag-ui': type === 'UI', 'tag-frontend': type === 'FRONTEND', 'tag-backend': type === 'BACKEND' }
}

function trimContent(content) {
  if (!content) return '暂无内容'
  const plain = content.replace(/[#*`>\-]/g, '').trim()
  return plain.length > 80 ? plain.substring(0, 80) + '...' : plain
}

function getProjectName(projectId) {
  return projects.value.find(p => p.projectId === projectId)?.name || projectId
}

function openCreateDialog() {
  editingSpec.value = null
  form.value = { name: '', specType: 'BACKEND', content: '' }
  formErrors.value = {}
  showDialog.value = true
}

function openEditDialog(spec) {
  editingSpec.value = spec
  form.value = { name: spec.name, specType: spec.specType, content: spec.content || '' }
  formErrors.value = {}
  showDialog.value = true
}

function closeDialog() {
  showDialog.value = false
}

function validate() {
  const errors = {}
  if (!form.value.name.trim()) errors.name = '名称不能为空'
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  submitting.value = true
  try {
    if (editingSpec.value) {
      await updateSpec(editingSpec.value.specId, {
        name: form.value.name,
        specType: form.value.specType,
        content: form.value.content,
      })
      ElMessage.success('规范已更新')
    } else {
      await createSpec({
        name: form.value.name,
        specType: form.value.specType,
        content: form.value.content,
      })
      ElMessage.success('规范已创建')
    }
    closeDialog()
    await loadSpecs()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(specId) {
  try {
    await deleteSpec(specId)
    ElMessage.success('规范已删除')
    await loadSpecs()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}

async function openLinkDialog(spec) {
  linkingSpec.value = spec
  selectedProjectId.value = ''
  linkedProjects.value = []
  showLinkDialog.value = true
  try {
    const res = await listSpecLinks(spec.specId)
    linkedProjects.value = res.data || []
  } catch (e) {
    ElMessage.error('加载关联项目失败')
  }
}

function closeLinkDialog() {
  showLinkDialog.value = false
  linkingSpec.value = null
}

async function handleLink() {
  if (!selectedProjectId.value || !linkingSpec.value) return
  linking.value = true
  try {
    await linkSpecToProject(linkingSpec.value.specId, selectedProjectId.value)
    ElMessage.success('关联成功，规范文件已写入项目 rules 目录')
    const res = await listSpecLinks(linkingSpec.value.specId)
    linkedProjects.value = res.data || []
    selectedProjectId.value = ''
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '关联失败')
  } finally {
    linking.value = false
  }
}

async function handleUnlink(projectId) {
  if (!linkingSpec.value) return
  unlinking.value = projectId
  try {
    await unlinkSpecFromProject(linkingSpec.value.specId, projectId)
    ElMessage.success('已解除关联')
    const res = await listSpecLinks(linkingSpec.value.specId)
    linkedProjects.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '解除失败')
  } finally {
    unlinking.value = ''
  }
}
</script>

<style scoped>
.spec-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 24px 32px;
  background: #F9F9F9;
  overflow-y: auto;
}

.spec-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.spec-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
  margin: 0;
}

.btn-create {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #0091FF;
  color: #fff;
  border: none;
  border-radius: 8px;
  height: 36px;
  padding: 0 14px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s;
}
.btn-create:hover { background: #0082E5; }
.btn-create:active { background: #0074CC; }

/* Tabs */
.spec-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
}

.spec-tab {
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  padding: 8px 16px;
  font-size: 14px;
  color: rgba(38, 38, 38, 0.76);
  cursor: pointer;
  margin-bottom: -1px;
  transition: color 0.15s, border-color 0.15s;
}
.spec-tab:hover { color: #262626; }
.spec-tab.active {
  color: #262626;
  border-bottom-color: #0091FF;
  font-weight: 500;
}

/* 列表 */
.spec-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.spec-empty {
  color: rgba(38, 38, 38, 0.36);
  font-size: 14px;
  text-align: center;
  padding: 48px 0;
}

.spec-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-radius: 8px;
  padding: 14px 16px;
  border: 1px solid rgba(38, 38, 38, 0.06);
  transition: box-shadow 0.15s;
}
.spec-card:hover {
  box-shadow: 0 2px 8px rgba(38, 38, 38, 0.08);
}

.spec-card-main { flex: 1; min-width: 0; }

.spec-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.spec-name {
  font-size: 14px;
  font-weight: 500;
  color: #262626;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.spec-type-tag {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  padding: 2px 7px;
  border-radius: 4px;
}
.tag-ui { background: rgba(115, 83, 233, 0.1); color: #7353E9; }
.tag-frontend { background: rgba(0, 145, 255, 0.1); color: #0091FF; }
.tag-backend { background: rgba(0, 136, 91, 0.1); color: #00885B; }

.spec-preview {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.56);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.spec-card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  margin-left: 12px;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.56);
  transition: background 0.15s, color 0.15s;
}
.action-btn:hover { background: rgba(38, 38, 38, 0.06); color: #262626; }
.action-delete:hover { color: #FF4D4F; background: rgba(255, 77, 79, 0.06); }

/* 弹窗通用 */
.sp-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sp-dialog {
  background: #fff;
  border-radius: 12px;
  width: 560px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.16);
}

.sp-dialog-link {
  width: 480px;
}

.sp-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 16px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
}

.sp-dialog-title {
  font-size: 15px;
  font-weight: 600;
  color: #262626;
}

.sp-dialog-close {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.56);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}
.sp-dialog-close:hover { background: rgba(38, 38, 38, 0.06); }

.sp-dialog-body {
  padding: 16px 24px;
  overflow-y: auto;
}

.sp-dialog-footer {
  padding: 12px 24px 20px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 表单 */
.sp-form { display: flex; flex-direction: column; gap: 16px; }

.sp-form-item { display: flex; flex-direction: column; gap: 6px; }

.sp-label {
  font-size: 13px;
  font-weight: 500;
  color: rgba(38, 38, 38, 0.76);
}

.sp-required { color: #FF4D4F; margin-left: 2px; }

.sp-input {
  height: 36px;
  padding: 0 12px;
  border: 1px solid rgba(38, 38, 38, 0.16);
  border-radius: 8px;
  font-size: 14px;
  color: #262626;
  outline: none;
  transition: border-color 0.15s;
}
.sp-input:focus { border-color: #0091FF; }
.sp-input.error { border-color: #FF4D4F; }

.sp-error { font-size: 12px; color: #FF4D4F; }

.sp-type-switch {
  display: flex;
  gap: 8px;
}

.sp-type-btn {
  flex: 1;
  height: 36px;
  border: 1px solid rgba(38, 38, 38, 0.16);
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: rgba(38, 38, 38, 0.76);
  cursor: pointer;
  transition: all 0.15s;
}
.sp-type-btn:hover { border-color: #0091FF; color: #0091FF; }
.sp-type-btn.active {
  border-color: #0091FF;
  background: rgba(0, 145, 255, 0.06);
  color: #0091FF;
  font-weight: 500;
}

.sp-textarea {
  padding: 10px 12px;
  border: 1px solid rgba(38, 38, 38, 0.16);
  border-radius: 8px;
  font-size: 14px;
  color: #262626;
  resize: vertical;
  font-family: ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, Consolas, monospace;
  line-height: 1.6;
  outline: none;
  transition: border-color 0.15s;
}
.sp-textarea:focus { border-color: #0091FF; }

.sp-btn-cancel {
  height: 36px;
  padding: 0 16px;
  border: 1px solid rgba(38, 38, 38, 0.16);
  border-radius: 8px;
  background: #fff;
  font-size: 14px;
  color: rgba(38, 38, 38, 0.76);
  cursor: pointer;
  transition: background 0.15s;
}
.sp-btn-cancel:hover { background: rgba(38, 38, 38, 0.04); }

.sp-btn-confirm {
  height: 36px;
  padding: 0 16px;
  border: none;
  border-radius: 8px;
  background: #0091FF;
  font-size: 14px;
  color: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.sp-btn-confirm:hover { background: #0082E5; }
.sp-btn-confirm:disabled { opacity: 0.5; cursor: not-allowed; }

/* 关联弹窗 */
.sp-link-section { margin-bottom: 20px; }

.sp-section-label {
  font-size: 13px;
  font-weight: 500;
  color: rgba(38, 38, 38, 0.76);
  margin: 0 0 10px;
}

.sp-link-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: rgba(38, 38, 38, 0.03);
  border-radius: 6px;
  margin-bottom: 6px;
}

.sp-link-project-name {
  font-size: 14px;
  color: #262626;
}

.sp-unlink-btn {
  font-size: 13px;
  color: #FF4D4F;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.15s;
}
.sp-unlink-btn:hover { background: rgba(255, 77, 79, 0.06); }
.sp-unlink-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.sp-link-empty {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.36);
  padding: 8px 0;
  margin-bottom: 20px;
}

.sp-link-add {
  display: flex;
  gap: 8px;
  align-items: center;
}

.sp-select {
  flex: 1;
  height: 36px;
  padding: 0 10px;
  border: 1px solid rgba(38, 38, 38, 0.16);
  border-radius: 8px;
  font-size: 14px;
  color: #262626;
  background: #fff;
  outline: none;
}
.sp-select:focus { border-color: #0091FF; }

.sp-link-confirm-btn {
  height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: 8px;
  background: #0091FF;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s;
}
.sp-link-confirm-btn:hover { background: #0082E5; }
.sp-link-confirm-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.sp-hint {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  margin: 6px 0 0;
}
</style>
