<template>
  <div class="settings-page">
    <div class="settings-header">
      <h2 class="settings-title">系统设置</h2>
    </div>

    <div class="settings-content" v-if="!loading">
      <div class="settings-section">
        <h3 class="section-title">基础配置</h3>
        <div class="section-desc">配置项目工作目录和 Claude Code CLI 程序路径</div>

        <div class="settings-form">
          <!-- 工作目录 -->
          <div class="form-item">
            <label class="form-label">项目工作目录</label>
            <div class="form-desc">Git 仓库拉取后的存储目录，每个项目存放在该目录下的子文件夹中</div>
            <div class="form-input-wrap" :class="{ focused: focusedField === 'workspace' }">
              <input
                v-model="form.workspaceBasePath"
                class="form-input"
                placeholder="/tmp/workspace/projects"
                @focus="focusedField = 'workspace'"
                @blur="focusedField = ''"
              />
            </div>
          </div>

          <!-- Claude CLI 路径 -->
          <div class="form-item">
            <label class="form-label">Claude Code CLI 路径</label>
            <div class="form-desc">Claude Code 命令行工具的可执行文件路径</div>
            <div class="form-input-wrap" :class="{ focused: focusedField === 'claude' }">
              <input
                v-model="form.claudeCliPath"
                class="form-input"
                placeholder="/usr/local/bin/claude"
                @focus="focusedField = 'claude'"
                @blur="focusedField = ''"
              />
            </div>
          </div>
        </div>

        <div class="settings-actions">
          <button class="btn-save" @click="handleSave" :disabled="saving || !hasChanged">
            {{ saving ? '保存中...' : '保存' }}
          </button>
          <button class="btn-reset" @click="resetForm" :disabled="!hasChanged">
            重置
          </button>
        </div>
      </div>
    </div>

    <div v-else class="settings-loading">加载中...</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getSettings, updateSettings } from '../api/settings'
import { ElMessage } from 'element-plus'

const loading = ref(true)
const saving = ref(false)
const focusedField = ref('')

const form = ref({ workspaceBasePath: '', claudeCliPath: '' })
const original = ref({ workspaceBasePath: '', claudeCliPath: '' })

const hasChanged = computed(() =>
  form.value.workspaceBasePath !== original.value.workspaceBasePath ||
  form.value.claudeCliPath !== original.value.claudeCliPath
)

onMounted(async () => {
  try {
    const res = await getSettings()
    form.value = { ...res.data }
    original.value = { ...res.data }
  } catch (e) {
    ElMessage.error('加载配置失败')
  } finally {
    loading.value = false
  }
})

async function handleSave() {
  if (!form.value.workspaceBasePath.trim()) {
    ElMessage.warning('请输入项目工作目录')
    return
  }
  if (!form.value.claudeCliPath.trim()) {
    ElMessage.warning('请输入 Claude CLI 路径')
    return
  }
  saving.value = true
  try {
    const res = await updateSettings(form.value)
    original.value = { ...res.data }
    form.value = { ...res.data }
    ElMessage.success('配置已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + e.message)
  } finally {
    saving.value = false
  }
}

function resetForm() {
  form.value = { ...original.value }
}
</script>

<style scoped>
.settings-page {
  padding: 24px 32px;
  max-width: 640px;
  font-family: -apple-system, system-ui, "Segoe UI", Roboto, "Helvetica Neue",
               "PingFang SC", "Noto Sans", "Noto Sans CJK SC",
               "Microsoft YaHei", sans-serif;
}

.settings-header {
  margin-bottom: 24px;
}

.settings-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
  margin: 0;
}

.settings-section {
  background: #fff;
}

.section-title {
  font-size: 14px;
  font-weight: 500;
  color: #262626;
  margin: 0 0 4px;
}

.section-desc {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  margin-bottom: 20px;
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-label {
  font-size: 14px;
  font-weight: 500;
  color: #262626;
}

.form-desc {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  margin-bottom: 4px;
}

.form-input-wrap {
  display: flex;
  align-items: center;
  height: 36px;
  border: 1px solid rgba(38, 38, 38, 0.22);
  border-radius: 8px;
  padding: 0 8px;
  transition: border-color 0.2s;
}
.form-input-wrap:hover {
  border-color: rgba(38, 38, 38, 0.36);
}
.form-input-wrap.focused {
  border-color: #0091FF;
}

.form-input {
  flex: 1;
  height: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: #262626;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
}
.form-input::placeholder {
  color: rgba(38, 38, 38, 0.36);
  font-family: inherit;
}

.settings-actions {
  display: flex;
  gap: 10px;
  margin-top: 24px;
}

.btn-save {
  height: 36px;
  padding: 0 16px;
  background: #0091FF;
  color: #fff;
  border: 1px solid transparent;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
  font-family: inherit;
}
.btn-save:hover:not(:disabled) {
  background: #0082E5;
}
.btn-save:active:not(:disabled) {
  background: #0074CC;
}
.btn-save:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-reset {
  height: 36px;
  padding: 0 16px;
  background: transparent;
  color: #262626;
  border: 1px solid rgba(0, 145, 255, 0.16);
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  font-family: inherit;
}
.btn-reset:hover:not(:disabled) {
  border-color: rgba(0, 145, 255, 0.36);
  background: rgba(0, 145, 255, 0.04);
}
.btn-reset:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.settings-loading {
  font-size: 14px;
  color: rgba(38, 38, 38, 0.36);
  padding: 40px 0;
  text-align: center;
}
</style>
