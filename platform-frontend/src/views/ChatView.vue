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
      </div>

      <div class="chat-input">
        <div class="input-box">
          <!-- 已上传文件预览：输入框内部上方 -->
          <div v-if="uploadedFiles.length > 0" class="uploaded-files">
            <div v-for="(f, i) in uploadedFiles" :key="i" class="uploaded-file-tag">
              <el-icon class="file-icon"><Document /></el-icon>
              <span class="file-name">{{ f.name }}</span>
              <el-icon class="file-remove" @click="uploadedFiles.splice(i, 1)"><Close /></el-icon>
            </div>
          </div>
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="3"
            :placeholder="uploadedFiles.length > 0 ? '描述你想对文件做什么...' : '输入需求描述，例如：创建一个员工管理页面...'"
            @keydown.enter.ctrl="handleSend"
          />
        </div>
        <input ref="fileInputRef" type="file" hidden @change="handleFileSelected" />
        <div class="input-actions">
          <div class="input-left">
            <el-tooltip content="添加文件等" placement="top" :show-after="300">
              <button class="add-file-btn" :disabled="loading || uploading" @click="fileInputRef?.click()">
                <el-icon><Plus /></el-icon>
              </button>
            </el-tooltip>
            <span class="input-hint" v-if="!loading">Ctrl + Enter 发送</span>
            <span class="input-hint cancel-hint" v-else>按 ESC 取消生成</span>
          </div>
          <div class="action-buttons">
            <el-button v-if="loading" @click="handleCancel" type="danger" plain size="small">
              取消
            </el-button>
            <el-button type="primary" @click="handleSend" :disabled="!inputMessage.trim() || loading">
              发送
            </el-button>
          </div>
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
import { getProject } from '../api/project'
import { listThreads, createThread, getThreadMessages, sendThreadMessage, cancelThread, uploadThreadFile } from '../api/thread'
import { connectWebSocket, disconnectWebSocket, subscribeThread, unsubscribeThread } from '../api/websocket'
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
const currentThreadId = ref(null)
const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const messagesRef = ref(null)
const activeTab = ref(mode === 'prd' ? 'prd' : 'code')
const codePreviewRef = ref(null)
const latestTestResult = ref(null)
const prdContent = ref('')
const deployPanelRef = ref(null)
const uploadedFiles = ref([])
const uploading = ref(false)
const fileInputRef = ref(null)

onMounted(async () => {
  await loadProject()
  await initThread()
  initWebSocket()
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  disconnectWebSocket()
  document.removeEventListener('keydown', handleKeydown)
})

async function loadProject() {
  try {
    const res = await getProject(projectId)
    project.value = res.data
  } catch (e) {
    ElMessage.error('加载项目失败')
  }
}

async function initThread() {
  try {
    // 获取项目线程列表，选第一个；没有则创建
    const res = await listThreads(projectId)
    const threads = res.data || []
    if (threads.length > 0) {
      currentThreadId.value = threads[0].threadId
      await loadMessages(threads[0].threadId)
    } else {
      const createRes = await createThread(projectId)
      currentThreadId.value = createRes.data.threadId
    }
  } catch (e) {
    // 容错：创建默认线程
    try {
      const createRes = await createThread(projectId)
      currentThreadId.value = createRes.data.threadId
    } catch (_) { /* ignore */ }
  }
}

function initWebSocket() {
  connectWebSocket(() => {
    if (currentThreadId.value) {
      subscribeThread(currentThreadId.value, onWsMessage)
    }
  })
}

function onWsMessage(msg) {
  messages.value.push(msg)

  if (msg.messageType === 'prd') {
    prdContent.value = msg.content
    activeTab.value = 'prd'
  }

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
}

async function loadMessages(threadId) {
  try {
    const res = await getThreadMessages(threadId)
    messages.value = (res.data || []).map(c => ({
      role: c.role,
      content: c.content,
      messageType: c.messageType
    }))
    scrollToBottom()
  } catch (e) {
    // 空线程
  }
}

function handlePrdToDev() {
  if (!prdContent.value) return
  const prompt = '请根据以下 PRD 文档生成代码：\n\n' + prdContent.value
  inputMessage.value = prompt
  activeTab.value = 'code'
  ElMessage.success('PRD 内容已填入，点击发送开始生成代码')
}

function handleKeydown(e) {
  if (e.key === 'Escape' && loading.value && currentThreadId.value) {
    handleCancel()
  }
}

async function handleCancel() {
  if (!loading.value || !currentThreadId.value) return
  try {
    await cancelThread(currentThreadId.value)
    loading.value = false
    messages.value.push({
      role: 'system',
      content: '已取消生成',
      messageType: 'progress'
    })
    scrollToBottom()
  } catch (e) {
    ElMessage.error('取消失败: ' + e.message)
  }
}

async function handleFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file || !currentThreadId.value) return

  // 重置 input 以允许再次选择同一文件
  event.target.value = ''

  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 10MB')
    return
  }

  uploading.value = true
  try {
    const res = await uploadThreadFile(currentThreadId.value, file)
    uploadedFiles.value.push({ name: file.name, path: res.data })
    ElMessage.success('文件已上传: ' + file.name)
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

async function handleSend() {
  const content = inputMessage.value.trim()
  if (!content || !currentThreadId.value) return

  // 收集已上传文件路径
  const filePaths = uploadedFiles.value.map(f => f.path)
  const displayContent = filePaths.length > 0
    ? content + '\n\n📎 ' + uploadedFiles.value.map(f => f.name).join(', ')
    : content

  messages.value.push({ role: 'user', content: displayContent, messageType: 'text' })
  inputMessage.value = ''
  uploadedFiles.value = []
  loading.value = true
  scrollToBottom()

  try {
    await sendThreadMessage(currentThreadId.value, content, filePaths)
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
  border-right: 1px solid rgba(38, 38, 38, 0.06);
}
.chat-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
}
.project-title {
  font-weight: 600;
  font-size: 16px;
  color: #262626;
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
  background: #0091FF;
  color: white;
  display: inline-block;
  padding: 10px 14px;
  border-radius: 12px 12px 0 12px;
}
.message-assistant .message-content {
  background: rgba(38, 38, 38, 0.04);
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
  color: rgba(38, 38, 38, 0.76);
  font-size: 13px;
  background: rgba(0, 136, 91, 0.06);
  padding: 6px 14px;
  border-radius: 16px;
  border: 1px solid rgba(0, 136, 91, 0.12);
}
.message-system {
  max-width: 100%;
  text-align: center;
  color: rgba(38, 38, 38, 0.36);
}
.message-role {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  margin-bottom: 4px;
}
.chat-input {
  padding: 12px 16px;
  border-top: 1px solid rgba(38, 38, 38, 0.06);
}
.input-box {
  border: 1px solid rgba(38, 38, 38, 0.12);
  border-radius: 12px;
  overflow: hidden;
  transition: border-color 0.2s;
}
.input-box:focus-within {
  border-color: rgba(0, 145, 255, 0.4);
}
.input-box :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 12px 14px;
  resize: none;
}
.uploaded-files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 14px 0;
}
.uploaded-file-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(38, 38, 38, 0.04);
  border: 1px solid rgba(38, 38, 38, 0.1);
  border-radius: 20px;
  font-size: 13px;
  color: #262626;
}
.uploaded-file-tag .file-icon {
  font-size: 14px;
  color: rgba(38, 38, 38, 0.56);
}
.uploaded-file-tag .file-name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.uploaded-file-tag .file-remove {
  cursor: pointer;
  color: rgba(38, 38, 38, 0.28);
  font-size: 12px;
  transition: color 0.15s;
}
.uploaded-file-tag .file-remove:hover {
  color: rgba(38, 38, 38, 0.76);
}
.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.input-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.add-file-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid rgba(38, 38, 38, 0.12);
  background: #fff;
  color: rgba(38, 38, 38, 0.56);
  cursor: pointer;
  transition: all 0.15s;
  font-size: 16px;
}
.add-file-btn:hover:not(:disabled) {
  background: rgba(38, 38, 38, 0.04);
  color: #262626;
  border-color: rgba(38, 38, 38, 0.2);
}
.add-file-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.input-hint {
  color: rgba(38, 38, 38, 0.36);
  font-size: 12px;
}
.input-hint.cancel-hint {
  color: rgba(255, 154, 33, 1);
}
.action-buttons {
  display: flex;
  gap: 8px;
}
.preview-panel {
  width: 45%;
  padding: 16px;
  overflow-y: auto;
}
</style>
