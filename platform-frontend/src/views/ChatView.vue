<template>
  <div class="chat-page">
    <!-- 左侧：对话区 -->
    <div class="chat-panel">
      <div class="chat-header">
        <el-button text @click="$router.push('/')">
          <el-icon><ArrowLeft /></el-icon>
        </el-button>
        <span class="project-title">{{ project?.name || '加载中...' }}</span>
        <el-tag :type="statusTagType(project?.status)" size="small">{{ project?.status }}</el-tag>
      </div>

      <div class="chat-messages" ref="messagesRef">
        <div v-for="(msg, index) in messages" :key="index"
             :class="['message', `message-${msg.role}`, { 'message-progress': msg.messageType === 'progress' }]">
          <div class="message-role" v-if="msg.messageType !== 'progress'">{{ roleLabel(msg.role) }}</div>
          <div v-if="msg.messageType === 'progress'" class="progress-content">
            <el-icon><Loading /></el-icon> {{ msg.content }}
          </div>
          <div v-else class="message-content markdown-body" v-html="renderContent(msg)"></div>
        </div>
        <div v-if="loading" class="message message-system">
          <el-icon class="is-loading"><Loading /></el-icon>
          AI 正在思考...
        </div>
      </div>

      <div class="chat-input">
        <el-input
          v-model="inputMessage"
          type="textarea"
          :rows="3"
          placeholder="输入需求描述，例如：创建一个员工管理页面，包含姓名、工号、部门字段..."
          @keydown.enter.ctrl="handleSend"
        />
        <div class="input-actions">
          <span class="input-hint">Ctrl + Enter 发送</span>
          <el-button type="primary" @click="handleSend" :disabled="!inputMessage.trim() || loading">
            发送
          </el-button>
        </div>
      </div>
    </div>

    <!-- 右侧：预览区 -->
    <div class="preview-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="PRD" name="prd">
          <PrdPreview :prd-content="prdContent" :project-id="projectId"
                      @start-dev="handlePrdToDev" />
        </el-tab-pane>
        <el-tab-pane label="代码" name="code">
          <CodePreview :project-id="projectId" ref="codePreviewRef" />
        </el-tab-pane>
        <el-tab-pane label="测试" name="test">
          <TestReport :test-result="latestTestResult" />
        </el-tab-pane>
        <el-tab-pane label="部署" name="deploy">
          <DeployPanel :project-id="projectId" ref="deployPanelRef" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getProject, getConversations, sendMessage } from '../api/project'
import { connectWebSocket, disconnectWebSocket } from '../api/websocket'
import { ElMessage } from 'element-plus'
import CodePreview from '../components/CodePreview.vue'
import TestReport from '../components/TestReport.vue'
import PrdPreview from '../components/PrdPreview.vue'
import DeployPanel from '../components/DeployPanel.vue'
import MarkdownIt from 'markdown-it'
import hljs from '../utils/hljs'

const md = new MarkdownIt({
  highlight: function (str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return '<pre class="hljs"><code>' + hljs.highlight(str, { language: lang }).value + '</code></pre>'
      } catch (_) { /* ignore */ }
    }
    return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>'
  }
})

const route = useRoute()
const projectId = route.params.projectId
const mode = route.query.mode || 'chat'

const project = ref(null)
const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const messagesRef = ref(null)
const activeTab = ref(mode === 'prd' ? 'prd' : 'code')
const codePreviewRef = ref(null)
const latestTestResult = ref(null)
const prdContent = ref('')
const deployPanelRef = ref(null)

onMounted(async () => {
  await loadProject()
  await loadHistory()
  connectWs()
})

onUnmounted(() => {
  disconnectWebSocket()
})

async function loadProject() {
  try {
    const res = await getProject(projectId)
    project.value = res.data
  } catch (e) {
    ElMessage.error('加载项目失败')
  }
}

async function loadHistory() {
  try {
    const res = await getConversations(projectId)
    messages.value = (res.data || []).map(c => ({
      role: c.role,
      content: c.content,
      messageType: c.messageType
    }))
    scrollToBottom()
  } catch (e) {
    // 新项目可能没有历史
  }
}

function connectWs() {
  connectWebSocket(projectId, (msg) => {
    messages.value.push(msg)

    // PRD 内容更新
    if (msg.messageType === 'prd') {
      prdContent.value = msg.content
      activeTab.value = 'prd'
    }

    // 部署进度更新
    if (msg.messageType === 'progress' && deployPanelRef.value) {
      const deployKeywords = ['打包', '构建', '推送', '部署', '健康检查', '回滚']
      if (deployKeywords.some(k => msg.content?.includes(k))) {
        deployPanelRef.value.updateProgress(msg.content)
      }
    }

    if (msg.messageType === 'progress' && msg.content?.includes('完成')) {
      loading.value = false
    }
    scrollToBottom()
  })
}

function handlePrdToDev() {
  if (!prdContent.value) return
  // 将 PRD 内容作为需求发送给代码生成流程
  const prompt = '请根据以下 PRD 文档生成代码：\n\n' + prdContent.value
  inputMessage.value = prompt
  activeTab.value = 'code'
  ElMessage.success('PRD 内容已填入，点击发送开始生成代码')
}

async function handleSend() {
  const content = inputMessage.value.trim()
  if (!content) return

  messages.value.push({ role: 'user', content, messageType: 'text' })
  inputMessage.value = ''
  loading.value = true
  scrollToBottom()

  try {
    await sendMessage(projectId, content)
  } catch (e) {
    loading.value = false
    ElMessage.error('发送失败: ' + e.message)
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function roleLabel(role) {
  const map = { user: '你', assistant: 'AI', system: '系统' }
  return map[role] || role
}

function renderContent(msg) {
  if (!msg.content) return ''
  if (msg.messageType === 'progress') {
    return msg.content
  }
  return md.render(msg.content)
}

function statusTagType(status) {
  const map = { creating: 'info', active: '', deploying: 'warning', deployed: 'success', failed: 'danger' }
  return map[status] || 'info'
}

watch(messages, () => scrollToBottom(), { deep: true })
</script>

<style scoped>
.chat-page {
  display: flex;
  height: 100%;
}
.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e4e7ed;
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
}
.project-title {
  font-weight: bold;
  font-size: 16px;
}
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.message {
  margin-bottom: 16px;
  max-width: 80%;
}
.message-user {
  margin-left: auto;
  text-align: right;
}
.message-user .message-content {
  background: #409eff;
  color: white;
  display: inline-block;
  padding: 10px 14px;
  border-radius: 12px 12px 0 12px;
}
.message-assistant .message-content {
  background: #f4f4f5;
  display: inline-block;
  padding: 10px 14px;
  border-radius: 12px 12px 12px 0;
  max-width: 100%;
}
.message-assistant .message-content :deep(pre) {
  background: #1e1e1e;
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
  margin: 8px 0;
}
.message-assistant .message-content :deep(pre code) {
  color: #d4d4d4;
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', Consolas, monospace;
}
.message-assistant .message-content :deep(p) {
  margin: 4px 0;
}
.message-assistant .message-content :deep(ul), .message-assistant .message-content :deep(ol) {
  padding-left: 20px;
  margin: 4px 0;
}
.message-progress {
  max-width: 100%;
  text-align: center;
}
.progress-content {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #909399;
  font-size: 13px;
  background: #f0f9eb;
  padding: 6px 14px;
  border-radius: 16px;
  border: 1px solid #e1f3d8;
}
.message-system {
  max-width: 100%;
  text-align: center;
  color: #909399;
}
.message-system .message-content {
  color: #909399;
  font-size: 13px;
  text-align: center;
}
.message-role {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.chat-input {
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
}
.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.input-hint {
  color: #909399;
  font-size: 12px;
}
.preview-panel {
  width: 45%;
  padding: 16px;
  overflow-y: auto;
}
.file-list {
  font-family: monospace;
}
.file-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 4px;
}
.file-item:hover {
  background: #f5f7fa;
}
</style>
