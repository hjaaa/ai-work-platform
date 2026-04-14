<template>
  <div class="skill-view">
    <!-- 页头 -->
    <div class="skill-header">
      <h2 class="skill-title">技能</h2>
      <button class="btn-create" @click="openCreateDialog">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        新建技能
      </button>
    </div>

    <!-- scope 过滤 tab -->
    <div class="skill-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        class="skill-tab"
        :class="{ active: activeTab === tab.value }"
        @click="switchTab(tab.value)"
      >{{ tab.label }}</button>
    </div>

    <!-- Skill 列表 -->
    <div class="skill-list">
      <div v-if="loading" class="skill-loading">加载中...</div>
      <div v-else-if="skills.length === 0" class="skill-empty">
        <span>暂无技能，点击「新建技能」添加</span>
      </div>
      <div
        v-else
        v-for="skill in skills"
        :key="skill.skillId"
        class="skill-card"
      >
        <div class="skill-card-main">
          <div class="skill-card-top">
            <span class="skill-name">{{ skill.name }}</span>
            <span class="skill-scope-tag" :class="skill.scope === 'PERSONAL' ? 'tag-personal' : 'tag-project'">
              {{ skill.scope === 'PERSONAL' ? '个人' : '项目' }}
            </span>
          </div>
          <p class="skill-desc">{{ trimDesc(skill.description) }}</p>
        </div>
        <div class="skill-card-actions">
          <button class="action-btn action-edit" title="编辑" @click="openEditDialog(skill)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
            </svg>
          </button>
          <el-popconfirm title="确认删除此技能？此操作将同时删除磁盘文件。" @confirm="handleDelete(skill.skillId)">
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
      <div v-if="showDialog" class="sk-overlay">
        <div class="sk-dialog">
          <div class="sk-dialog-header">
            <span class="sk-dialog-title">{{ editingSkill ? '编辑技能' : '新建技能' }}</span>
            <button class="sk-dialog-close" @click="closeDialog">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M1 1l10 10M11 1L1 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <div class="sk-dialog-body">
            <div class="sk-form">
              <!-- 名称 -->
              <div class="sk-form-item">
                <label class="sk-label">名称 <span class="sk-required">*</span></label>
                <input
                  v-model="form.name"
                  class="sk-input"
                  :class="{ error: formErrors.name }"
                  placeholder="Skill 名称"
                  maxlength="100"
                />
                <span v-if="formErrors.name" class="sk-error">{{ formErrors.name }}</span>
              </div>

              <!-- 作用域（仅新建时可选） -->
              <div v-if="!editingSkill" class="sk-form-item">
                <label class="sk-label">作用域</label>
                <div class="sk-type-switch">
                  <button
                    class="sk-type-btn"
                    :class="{ active: form.scope === 'PERSONAL' }"
                    @click="form.scope = 'PERSONAL'; form.projectId = ''"
                  >个人级</button>
                  <button
                    class="sk-type-btn"
                    :class="{ active: form.scope === 'PROJECT' }"
                    @click="form.scope = 'PROJECT'"
                  >项目级</button>
                </div>
                <p class="sk-hint">
                  {{ form.scope === 'PERSONAL' ? '存储在 ~/.claude/skills/，所有项目可用' : '存储在项目 codePath/.claude/skills/' }}
                </p>
              </div>

              <!-- 关联项目（scope=PROJECT 时显示） -->
              <div v-if="!editingSkill && form.scope === 'PROJECT'" class="sk-form-item">
                <label class="sk-label">关联项目 <span class="sk-required">*</span></label>
                <select
                  v-model="form.projectId"
                  class="sk-select"
                  :class="{ error: formErrors.projectId }"
                >
                  <option value="">请选择项目</option>
                  <option
                    v-for="project in projects"
                    :key="project.projectId"
                    :value="project.projectId"
                  >{{ project.name }}</option>
                </select>
                <span v-if="formErrors.projectId" class="sk-error">{{ formErrors.projectId }}</span>
              </div>

              <!-- 描述 -->
              <div class="sk-form-item">
                <label class="sk-label">描述（Markdown）</label>
                <textarea
                  v-model="form.description"
                  class="sk-textarea"
                  placeholder="输入 Skill 的描述或内容，支持 Markdown 格式..."
                  rows="8"
                ></textarea>
              </div>
            </div>
          </div>
          <div class="sk-dialog-footer">
            <button class="sk-btn-cancel" @click="closeDialog">取消</button>
            <button class="sk-btn-confirm" :disabled="submitting" @click="handleSubmit">
              {{ submitting ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listSkills, createSkill, updateSkill, deleteSkill } from '../api/skill'
import { listProjects } from '../api/project'

const tabs = [
  { label: '全部', value: '' },
  { label: '个人', value: 'PERSONAL' },
  { label: '项目', value: 'PROJECT' },
]
const activeTab = ref('')
const skills = ref([])
const projects = ref([])
const loading = ref(false)
const showDialog = ref(false)
const submitting = ref(false)
const editingSkill = ref(null)

const form = ref({ name: '', description: '', scope: 'PERSONAL', projectId: '' })
const formErrors = ref({})

onMounted(async () => {
  await Promise.all([loadSkills(), loadProjects()])
})

async function loadSkills() {
  loading.value = true
  try {
    const res = await listSkills({ scope: activeTab.value || undefined })
    // 过滤掉系统级 skill，管理页面不展示、不可编辑/删除
    skills.value = (res.data || []).filter(s => s.scope !== 'SYSTEM')
  } catch (e) {
    ElMessage.error('加载技能列表失败')
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } catch (e) {
    // 静默忽略，项目下拉框为空
  }
}

function switchTab(value) {
  activeTab.value = value
  loadSkills()
}

function trimDesc(desc) {
  if (!desc) return '暂无描述'
  return desc.length > 80 ? desc.substring(0, 80) + '...' : desc
}

function openCreateDialog() {
  editingSkill.value = null
  form.value = { name: '', description: '', scope: 'PERSONAL', projectId: '' }
  formErrors.value = {}
  showDialog.value = true
}

function openEditDialog(skill) {
  editingSkill.value = skill
  form.value = { name: skill.name, description: skill.description || '', scope: skill.scope, projectId: skill.projectId || '' }
  formErrors.value = {}
  showDialog.value = true
}

function closeDialog() {
  showDialog.value = false
}

function validate() {
  const errors = {}
  if (!form.value.name.trim()) {
    errors.name = '名称不能为空'
  }
  if (!editingSkill.value && form.value.scope === 'PROJECT' && !form.value.projectId) {
    errors.projectId = '请选择关联项目'
  }
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  submitting.value = true
  try {
    if (editingSkill.value) {
      await updateSkill(editingSkill.value.skillId, {
        name: form.value.name,
        description: form.value.description,
      })
      ElMessage.success('技能已更新')
    } else {
      await createSkill({
        name: form.value.name,
        description: form.value.description,
        scope: form.value.scope,
        projectId: form.value.scope === 'PROJECT' ? form.value.projectId : undefined,
      })
      ElMessage.success('技能已创建')
    }
    closeDialog()
    await loadSkills()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(skillId) {
  try {
    await deleteSkill(skillId)
    ElMessage.success('技能已删除')
    await loadSkills()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '删除失败')
  }
}
</script>

<style scoped>
.skill-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 24px 32px;
  background: #F9F9F9;
  overflow-y: auto;
}

.skill-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.skill-title {
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
.skill-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
  padding-bottom: 0;
}

.skill-tab {
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
.skill-tab:hover { color: #262626; }
.skill-tab.active {
  color: #262626;
  border-bottom-color: #0091FF;
  font-weight: 500;
}

/* List */
.skill-loading,
.skill-empty {
  color: rgba(38, 38, 38, 0.36);
  font-size: 14px;
  padding: 48px 0;
  text-align: center;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.skill-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-radius: 12px;
  padding: 16px 20px;
  box-shadow: rgba(38, 38, 38, 0.1) 0 1px 5px 0;
  transition: box-shadow 0.15s;
}
.skill-card:hover { box-shadow: rgba(38, 38, 38, 0.15) 0 2px 8px 0; }

.skill-card-main { flex: 1; min-width: 0; }

.skill-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.skill-name {
  font-size: 14px;
  font-weight: 600;
  color: #262626;
}

.skill-scope-tag {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 500;
}
.tag-personal {
  background: rgba(0, 145, 255, 0.1);
  color: #0091FF;
}
.tag-project {
  background: rgba(115, 83, 233, 0.1);
  color: #7353E9;
}

.skill-desc {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.76);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.skill-card-actions {
  display: flex;
  gap: 6px;
  margin-left: 16px;
  opacity: 0;
  transition: opacity 0.15s;
}
.skill-card:hover .skill-card-actions { opacity: 1; }

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: none;
  cursor: pointer;
  transition: background 0.15s;
}
.action-edit { color: rgba(38, 38, 38, 0.76); }
.action-edit:hover { background: rgba(38, 38, 38, 0.06); }
.action-delete { color: rgba(38, 38, 38, 0.76); }
.action-delete:hover { background: rgba(255, 80, 80, 0.1); color: #ff5050; }

/* Dialog */
.sk-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.sk-dialog {
  background: #fff;
  border-radius: 12px;
  width: 520px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: rgba(38, 38, 38, 0.2) 0 8px 32px;
}

.sk-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 16px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
}
.sk-dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
}
.sk-dialog-close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.36);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.sk-dialog-close:hover { background: rgba(38, 38, 38, 0.06); }

.sk-dialog-body { padding: 20px 24px; overflow-y: auto; }

.sk-form { display: flex; flex-direction: column; gap: 16px; }

.sk-form-item { display: flex; flex-direction: column; gap: 6px; }

.sk-label {
  font-size: 13px;
  font-weight: 500;
  color: rgba(38, 38, 38, 0.76);
}
.sk-required { color: #ff5050; }

.sk-input,
.sk-select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid rgba(38, 38, 38, 0.15);
  border-radius: 6px;
  font-size: 14px;
  color: #262626;
  background: #fff;
  outline: none;
  transition: border-color 0.15s;
}
.sk-input:focus,
.sk-select:focus { border-color: #0091FF; }
.sk-input.error,
.sk-select.error { border-color: #ff5050; }

.sk-textarea {
  padding: 10px 12px;
  border: 1px solid rgba(38, 38, 38, 0.15);
  border-radius: 6px;
  font-size: 13px;
  color: #262626;
  background: #fff;
  outline: none;
  resize: vertical;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  transition: border-color 0.15s;
}
.sk-textarea:focus { border-color: #0091FF; }

.sk-error { font-size: 12px; color: #ff5050; }

.sk-hint { font-size: 12px; color: rgba(38, 38, 38, 0.36); margin: 0; }

.sk-type-switch {
  display: flex;
  gap: 8px;
}
.sk-type-btn {
  flex: 1;
  height: 36px;
  border: 1px solid rgba(38, 38, 38, 0.15);
  border-radius: 6px;
  background: #fff;
  font-size: 14px;
  color: rgba(38, 38, 38, 0.76);
  cursor: pointer;
  transition: all 0.15s;
}
.sk-type-btn:hover { border-color: #0091FF; color: #0091FF; }
.sk-type-btn.active {
  border-color: #0091FF;
  background: rgba(0, 145, 255, 0.06);
  color: #0091FF;
  font-weight: 500;
}

.sk-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px 20px;
  border-top: 1px solid rgba(38, 38, 38, 0.06);
}

.sk-btn-cancel {
  height: 36px;
  padding: 0 16px;
  border: 1px solid rgba(38, 38, 38, 0.15);
  border-radius: 6px;
  background: #fff;
  font-size: 14px;
  color: rgba(38, 38, 38, 0.76);
  cursor: pointer;
}
.sk-btn-cancel:hover { border-color: rgba(38, 38, 38, 0.3); }

.sk-btn-confirm {
  height: 36px;
  padding: 0 20px;
  background: #0091FF;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s;
}
.sk-btn-confirm:hover:not(:disabled) { background: #0082E5; }
.sk-btn-confirm:disabled { background: rgba(0, 145, 255, 0.4); cursor: not-allowed; }
</style>
