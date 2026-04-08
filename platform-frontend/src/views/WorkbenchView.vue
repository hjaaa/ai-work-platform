<template>
  <div class="workbench">
    <!-- 左侧面板 -->
    <aside class="wb-sidebar">
      <!-- 顶部操作 -->
      <div class="wb-sidebar-actions">
        <div class="wb-action-item" @click="handleNewTask">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
          </svg>
          <span>新任务</span>
        </div>
        <div class="wb-action-item">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="7" height="7" rx="1"/>
            <rect x="14" y="3" width="7" height="7" rx="1"/>
            <rect x="3" y="14" width="7" height="7" rx="1"/>
            <rect x="14" y="14" width="7" height="7" rx="1"/>
          </svg>
          <span>技能和应用</span>
        </div>
      </div>

      <!-- 项目区块 -->
      <div class="wb-projects-section">
        <div class="wb-projects-header">
          <span class="wb-projects-title">项目</span>
          <div class="wb-projects-actions">
            <button class="wb-icon-btn" title="筛选">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <line x1="4" y1="6" x2="20" y2="6"/>
                <line x1="7" y1="12" x2="17" y2="12"/>
                <line x1="10" y1="18" x2="14" y2="18"/>
              </svg>
            </button>
            <button class="wb-icon-btn" title="新建项目" @click="showCreateDialog = true">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2"/>
                <line x1="12" y1="8" x2="12" y2="16"/>
                <line x1="8" y1="12" x2="16" y2="12"/>
              </svg>
            </button>
          </div>
        </div>
        <div class="wb-project-list">
          <div v-for="project in projects" :key="project.projectId" class="wb-project-group">
            <!-- 项目行 -->
            <div
              class="wb-project-item"
              :class="{ active: selectedProject?.projectId === project.projectId }"
              @click="toggleProject(project)"
            >
              <!-- 展开/收起箭头 -->
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                   class="wb-expand-icon" :class="{ expanded: expandedProjectId === project.projectId }">
                <polyline points="9 18 15 12 9 6"/>
              </svg>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
                <line x1="12" y1="22.08" x2="12" y2="12"/>
              </svg>
              <span class="wb-project-name">{{ project.name }}</span>
              <!-- 新增线程按钮：hover 项目行时显示 -->
              <button
                class="wb-new-thread-btn"
                :title="`在 ${project.name} 中开始新线程`"
                @click.stop="handleNewThread(project)"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
              </button>
            </div>
            <!-- 线程列表（展开时显示） -->
            <div v-if="expandedProjectId === project.projectId" class="wb-thread-list">
              <div
                v-for="thread in projectThreads"
                :key="thread.threadId"
                class="wb-thread-item"
                :class="{ active: currentThreadId === thread.threadId }"
                @click.stop="selectThread(project, thread)"
              >
                <span class="wb-thread-title">{{ thread.title || '新对话' }}</span>
                <span class="wb-thread-time">{{ formatTime(thread.updatedAt) }}</span>
              </div>
              <div v-if="projectThreads.length === 0" class="wb-thread-empty">暂无对话</div>
            </div>
          </div>
          <div v-if="projects.length === 0" class="wb-empty-hint">
            暂无项目
          </div>
        </div>
      </div>

    </aside>

    <!-- 主内容区 -->
    <div class="wb-main">
      <!-- 顶部标题 -->
      <div class="wb-main-header">
        <h2 class="wb-main-title">{{ chatMode ? (selectedProject?.name || '对话') : '新任务' }}</h2>
      </div>

      <!-- 欢迎页（未进入聊天模式） -->
      <template v-if="!chatMode">
        <!-- 中心区域 -->
        <div class="wb-main-center">
          <div class="wb-build-icon">
            <svg width="64" height="64" viewBox="0 0 24 24" fill="#262626">
              <path d="M11.376 24L10.776 23.544L10.44 22.8L10.776 21.312L11.16 19.392L11.472 17.856L11.76 15.96L11.928 15.336L11.904 15.288L11.784 15.312L10.344 17.28L8.16 20.232L6.432 22.056L6.024 22.224L5.304 21.864L5.376 21.192L5.784 20.616L8.16 17.568L9.6 15.672L10.536 14.592L10.512 14.448H10.464L4.128 18.576L3 18.72L2.496 18.264L2.568 17.52L2.808 17.28L4.704 15.96L9.432 13.32L9.504 13.08L9.432 12.96H9.192L8.4 12.912L5.712 12.84L3.384 12.744L1.104 12.624L0.528 12.504L0 11.784L0.048 11.424L0.528 11.112L1.224 11.16L2.736 11.28L5.016 11.424L6.672 11.52L9.12 11.784H9.504L9.552 11.616L9.432 11.52L9.336 11.424L6.96 9.84L4.416 8.16L3.072 7.176L2.352 6.672L1.992 6.216L1.848 5.208L2.496 4.488L3.384 4.56L3.6 4.608L4.488 5.304L6.384 6.768L8.88 8.616L9.24 8.904L9.408 8.808V8.736L9.24 8.472L7.896 6.024L6.456 3.528L5.808 2.496L5.64 1.872C5.576 1.656 5.544 1.416 5.544 1.152L6.288 0.144001L6.696 0L7.704 0.144001L8.112 0.504001L8.736 1.92L9.72 4.152L11.28 7.176L11.736 8.088L11.976 8.904L12.072 9.168H12.24V9.024L12.36 7.296L12.6 5.208L12.84 2.52L12.912 1.752L13.296 0.840001L14.04 0.360001L14.616 0.624001L15.096 1.32L15.024 1.752L14.76 3.6L14.184 6.504L13.824 8.472H14.04L14.28 8.208L15.264 6.912L16.92 4.848L17.64 4.032L18.504 3.12L19.056 2.688H20.088L20.832 3.816L20.496 4.992L19.44 6.336L18.552 7.464L17.28 9.168L16.512 10.536L16.584 10.632H16.752L19.608 10.008L21.168 9.744L22.992 9.432L23.832 9.816L23.928 10.2L23.592 11.016L21.624 11.496L19.32 11.952L15.888 12.768L15.84 12.792L15.888 12.864L17.424 13.008L18.096 13.056H19.728L22.752 13.272L23.544 13.8L24 14.424L23.928 14.928L22.704 15.528L21.072 15.144L17.232 14.232L15.936 13.92H15.744V14.016L16.848 15.096L18.84 16.896L21.36 19.224L21.48 19.8L21.168 20.28L20.832 20.232L18.624 18.552L17.76 17.808L15.84 16.2H15.72V16.368L16.152 17.016L18.504 20.544L18.624 21.624L18.456 21.96L17.832 22.176L17.184 22.056L15.792 20.136L14.376 17.952L13.224 16.008L13.104 16.104L12.408 23.352L12.096 23.712L11.376 24Z" shape-rendering="optimizeQuality"/>
            </svg>
          </div>
          <div class="wb-build-text">开始构建</div>
          <div class="wb-project-selector-wrapper" v-click-outside="() => showProjectDropdown = false">
            <div class="wb-project-selector" @click="showProjectDropdown = !showProjectDropdown">
              <span>{{ selectedProject?.name || '选择项目' }}</span>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :class="{ 'wb-chevron-up': showProjectDropdown }">
                <polyline points="6 9 12 15 18 9"/>
              </svg>
            </div>
            <div v-if="showProjectDropdown" class="wb-project-dropdown">
              <div
                v-for="project in projects"
                :key="project.projectId"
                class="wb-dropdown-item"
                :class="{ active: selectedProject?.projectId === project.projectId }"
                @click="selectProjectFromDropdown(project)"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                  <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
                  <line x1="12" y1="22.08" x2="12" y2="12"/>
                </svg>
                <span>{{ project.name }}</span>
              </div>
              <div v-if="projects.length === 0" class="wb-dropdown-empty">暂无项目</div>
            </div>
          </div>
        </div>

        <!-- 底部输入区 -->
        <div class="wb-input-area">
          <div class="wb-input-box">
            <div v-if="uploadedFiles.length > 0" class="wb-uploaded-files">
              <div v-for="(f, i) in uploadedFiles" :key="i" class="wb-file-tag">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                <span class="wb-file-name">{{ f.name }}</span>
                <button class="wb-file-remove" @click="uploadedFiles.splice(i, 1)">&times;</button>
              </div>
            </div>
            <textarea
              v-model="inputText"
              class="wb-textarea"
              :placeholder="uploadedFiles.length > 0 ? '描述你想对文件做什么...' : '输入任务描述，@ 添加文件，/ 使用命令'"
              rows="2"
              @keydown.enter.exact="handleSend"
              @input="autoResize"
              ref="textareaRef"
            ></textarea>
            <div class="wb-input-toolbar">
              <div class="wb-toolbar-left">
                <button class="wb-toolbar-btn wb-add-btn" title="添加文件" :disabled="uploading" @click="fileInputRef?.click()">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="5" x2="12" y2="19"/>
                    <line x1="5" y1="12" x2="19" y2="12"/>
                  </svg>
                </button>
              </div>
              <div class="wb-toolbar-right">
                <button class="wb-send-btn" :class="{ active: inputText.trim() }" :disabled="!inputText.trim()" @click="handleSend">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="19" x2="12" y2="5"/>
                    <polyline points="5 12 12 5 19 12"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- 聊天模式 -->
      <template v-else>
        <!-- 消息列表 -->
        <div class="chat-messages" ref="messagesRef">
          <div v-for="(msg, index) in messages" :key="index" class="chat-msg-row">
            <!-- 用户消息：右上角标签样式 -->
            <template v-if="msg.role === 'user'">
              <div class="chat-user-msg">
                <span class="chat-user-tag">{{ msg.content }}</span>
              </div>
            </template>
            <!-- AI 回复：左对齐 Markdown -->
            <template v-else-if="msg.role === 'assistant'">
              <div class="chat-ai-msg">
                <div class="chat-ai-content markdown-body" v-html="renderMarkdown(msg.content)"></div>
              </div>
            </template>
            <!-- 进度消息 -->
            <template v-else-if="msg.messageType === 'progress'">
              <!-- 完成消息：分隔线样式 -->
              <div v-if="msg.content?.includes('完毕') || msg.content?.includes('完成')" class="chat-progress-divider">
                <span class="divider-line"></span>
                <span class="divider-text">{{ formatProgressText(msg.content) }} &gt;</span>
                <span class="divider-line"></span>
              </div>
              <!-- 进行中消息：spinner 样式 -->
              <div v-else class="chat-progress-msg">
                <svg class="chat-spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12 2v4m0 12v4m-7.07-3.93l2.83-2.83m8.48-8.48l2.83-2.83M2 12h4m12 0h4m-3.93 7.07l-2.83-2.83M7.76 7.76L4.93 4.93"/>
                </svg>
                <span>{{ msg.content }}</span>
              </div>
            </template>
          </div>
          <!-- 加载指示器 -->
          <div v-if="loading" class="chat-loading">
            <div class="chat-loading-dots">
              <span></span><span></span><span></span>
            </div>
          </div>
        </div>

        <!-- 底部输入区（聊天模式） -->
        <div class="wb-input-area">
          <div class="wb-input-box">
            <div v-if="uploadedFiles.length > 0" class="wb-uploaded-files">
              <div v-for="(f, i) in uploadedFiles" :key="i" class="wb-file-tag">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                <span class="wb-file-name">{{ f.name }}</span>
                <button class="wb-file-remove" @click="uploadedFiles.splice(i, 1)">&times;</button>
              </div>
            </div>
            <textarea
              v-model="inputText"
              class="wb-textarea"
              :placeholder="uploadedFiles.length > 0 ? '描述你想对文件做什么...' : '要求后续变更'"
              rows="2"
              @keydown.enter.exact="handleChatSend"
              @input="autoResize"
              ref="chatTextareaRef"
            ></textarea>
            <div class="wb-input-toolbar">
              <div class="wb-toolbar-left">
                <button class="wb-toolbar-btn wb-add-btn" title="添加文件" :disabled="uploading" @click="fileInputRef?.click()">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="5" x2="12" y2="19"/>
                    <line x1="5" y1="12" x2="19" y2="12"/>
                  </svg>
                </button>
              </div>
              <div class="wb-toolbar-right">
                <button class="wb-send-btn" :class="{ active: inputText.trim() && !loading }" :disabled="!inputText.trim() || loading" @click="handleChatSend">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="19" x2="12" y2="5"/>
                    <polyline points="5 12 12 5 19 12"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
      </template>
      <input ref="fileInputRef" type="file" hidden @change="handleFileSelected" />
    </div>

    <!-- 新建项目对话框 - Teambition 风格 -->
    <Teleport to="body">
      <div v-if="showCreateDialog" class="tb-overlay" @click.self="showCreateDialog = false">
        <div class="tb-dialog">
          <!-- Header -->
          <div class="tb-dialog-header">
            <span class="tb-dialog-title">新建项目</span>
            <button class="tb-dialog-close" @click="showCreateDialog = false">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M1 1l10 10M11 1L1 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <!-- Body -->
          <div class="tb-dialog-body">
            <div class="tb-form">
              <!-- 项目名称（必填） -->
              <div class="tb-form-item">
                <label class="tb-label">项目名称 <span class="tb-required">*</span></label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'name' }">
                  <input
                    v-model="createForm.name"
                    class="tb-input"
                    placeholder="例如：员工管理系统"
                    @focus="focusedField = 'name'"
                    @blur="focusedField = ''"
                  />
                </div>
              </div>
              <!-- Git 仓库地址（必填） -->
              <div class="tb-form-item">
                <label class="tb-label">Git 仓库地址 <span class="tb-required">*</span></label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'gitUrl' }">
                  <input
                    v-model="createForm.gitUrl"
                    class="tb-input"
                    placeholder="https://github.com/user/repo.git"
                    @focus="focusedField = 'gitUrl'"
                    @blur="focusedField = ''"
                  />
                </div>
              </div>
              <!-- 默认分支（选填） -->
              <div class="tb-form-item">
                <label class="tb-label">默认分支</label>
                <div class="tb-input-wrap" :class="{ focused: focusedField === 'branch' }">
                  <input
                    v-model="createForm.defaultBranch"
                    class="tb-input"
                    placeholder="main"
                    @focus="focusedField = 'branch'"
                    @blur="focusedField = ''"
                  />
                </div>
              </div>
              <!-- 项目描述（选填） -->
              <div class="tb-form-item">
                <label class="tb-label">项目描述</label>
                <div class="tb-textarea-wrap" :class="{ focused: focusedField === 'desc' }">
                  <textarea
                    v-model="createForm.description"
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
            <button class="tb-btn tb-btn-cancel" @click="showCreateDialog = false">取消</button>
            <button class="tb-btn tb-btn-primary" @click="handleCreate" :disabled="creating">
              {{ creating ? '提交中...' : '确定' }}
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
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import ModeSelector from '../components/ModeSelector.vue'
import { listProjects, createProject } from '../api/project'
import { listThreads, createThread, getThreadMessages, sendThreadMessage, uploadThreadFile } from '../api/thread'
import { connectWebSocket, disconnectWebSocket, subscribeThread, unsubscribeThread } from '../api/websocket'
import { ElMessage } from 'element-plus'
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

// 自定义指令：点击外部关闭
const vClickOutside = {
  mounted(el, binding) {
    el._clickOutside = (e) => {
      if (!el.contains(e.target)) {
        binding.value(e)
      }
    }
    document.addEventListener('click', el._clickOutside)
  },
  unmounted(el) {
    document.removeEventListener('click', el._clickOutside)
  }
}

const router = useRouter()
const projects = ref([])
const selectedProject = ref(null)
const showCreateDialog = ref(false)
const showModeSelector = ref(false)
const showProjectDropdown = ref(false)
const newProjectId = ref('')
const creating = ref(false)
const createForm = ref({ name: '', gitUrl: '', defaultBranch: '', description: '' })
const focusedField = ref('')
const inputText = ref('')
const textareaRef = ref(null)
const chatTextareaRef = ref(null)
const fileInputRef = ref(null)
const uploadedFiles = ref([])
const uploading = ref(false)

// 线程相关
const expandedProjectId = ref(null)
const projectThreads = ref([])
const currentThreadId = ref(null)
const lastThreadProject = ref(null) // 上次点击线程所属的项目

// 聊天模式相关
const chatMode = ref(false)
const messages = ref([])
const loading = ref(false)
const messagesRef = ref(null)

onMounted(async () => {
  await loadProjects()
})

onUnmounted(() => {
  disconnectWebSocket()
})

async function loadProjects() {
  try {
    const res = await listProjects()
    projects.value = res.data || []
    if (projects.value.length > 0 && !selectedProject.value) {
      selectedProject.value = projects.value[0]
    }
  } catch (e) {
    ElMessage.error('加载项目列表失败')
  }
}

async function toggleProject(project) {
  selectedProject.value = project
  if (expandedProjectId.value === project.projectId) {
    // 收起
    expandedProjectId.value = null
    projectThreads.value = []
    return
  }
  // 展开并加载线程
  expandedProjectId.value = project.projectId
  try {
    const res = await listThreads(project.projectId)
    projectThreads.value = res.data || []
  } catch (e) {
    projectThreads.value = []
  }
}

async function selectThread(project, thread) {
  selectedProject.value = project
  lastThreadProject.value = project
  currentThreadId.value = thread.threadId
  await enterChatModeForThread(thread.threadId)
}

// 在指定项目中创建新线程并进入聊天模式
const creatingThread = ref(false)
async function handleNewThread(project) {
  // 防抖：正在创建中则忽略重复点击
  if (creatingThread.value) return
  creatingThread.value = true
  selectedProject.value = project
  lastThreadProject.value = project
  try {
    const res = await createThread(project.projectId)
    const thread = res.data
    currentThreadId.value = thread.threadId
    // 刷新侧边栏线程列表，避免重复插入已复用的线程
    if (expandedProjectId.value === project.projectId) {
      const exists = projectThreads.value.some(t => t.threadId === thread.threadId)
      if (!exists) {
        projectThreads.value.unshift(thread)
      }
    } else {
      expandedProjectId.value = project.projectId
      const listRes = await listThreads(project.projectId)
      projectThreads.value = listRes.data || []
    }
    await enterChatModeForThread(thread.threadId)
  } catch (e) {
    ElMessage.error('创建线程失败')
  } finally {
    creatingThread.value = false
  }
}

function formatTime(dateStr) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now - date
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 60 * 60 * 1000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 24 * 60 * 60 * 1000) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < 7 * 24 * 60 * 60 * 1000) return Math.floor(diff / 86400000) + ' 天前'
  return date.toLocaleDateString()
}

// 新任务：根据优先级确定项目并新建线程
async function handleNewTask() {
  const targetProject = lastThreadProject.value || selectedProject.value || projects.value[0]
  if (!targetProject) {
    ElMessage.warning('请先创建一个项目')
    return
  }
  await handleNewThread(targetProject)
}

function selectProjectFromDropdown(project) {
  selectedProject.value = project
  showProjectDropdown.value = false
}

async function handleCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入项目名称')
    return
  }
  if (!createForm.value.gitUrl.trim()) {
    ElMessage.warning('请输入 Git 仓库地址')
    return
  }
  creating.value = true
  try {
    const res = await createProject(createForm.value)
    ElMessage.success('项目创建成功，正在拉取代码...')
    showCreateDialog.value = false
    newProjectId.value = res.data.projectId
    createForm.value = { name: '', gitUrl: '', defaultBranch: '', description: '' }
    await loadProjects()
    showModeSelector.value = true
  } catch (e) {
    ElMessage.error('创建失败: ' + e.message)
  } finally {
    creating.value = false
  }
}

function handleModeSelect(mode) {
  router.push(`/project/${newProjectId.value}?mode=${mode}`)
}

// 欢迎页发送：进入聊天模式
function handleSend() {
  if (!inputText.value.trim()) return
  if (!selectedProject.value) {
    ElMessage.warning('请先选择一个项目')
    return
  }
  enterChatMode(inputText.value.trim())
}

// 进入聊天模式（为指定线程）
async function enterChatModeForThread(threadId) {
  chatMode.value = true
  currentThreadId.value = threadId

  // 加载线程历史消息
  await loadThreadHistory(threadId)

  // 连接 WebSocket 并订阅线程
  connectWsForThread(threadId)
}

// 进入聊天模式（从欢迎页发起，自动创建线程）
async function enterChatMode(firstMessage) {
  chatMode.value = true
  const projectId = selectedProject.value.projectId

  // 创建新线程
  try {
    const res = await createThread(projectId)
    const thread = res.data
    currentThreadId.value = thread.threadId
    // 刷新侧边栏线程列表
    if (expandedProjectId.value === projectId) {
      projectThreads.value.unshift(thread)
    }
  } catch (e) {
    ElMessage.error('创建对话失败')
    return
  }

  // 连接 WebSocket 并订阅线程
  connectWsForThread(currentThreadId.value)

  // 发送第一条消息
  await nextTick()
  if (firstMessage) {
    inputText.value = ''
    await doSendMessage(currentThreadId.value, firstMessage)
  }
}

// 聊天模式发送
function handleChatSend() {
  if (!inputText.value.trim() || loading.value || !currentThreadId.value) return
  const content = inputText.value.trim()
  inputText.value = ''
  doSendMessage(currentThreadId.value, content)
}

async function doSendMessage(threadId, content) {
  // 收集已上传文件路径
  const filePaths = uploadedFiles.value.map(f => f.path)
  const displayContent = filePaths.length > 0
    ? content + '\n\n📎 ' + uploadedFiles.value.map(f => f.name).join(', ')
    : content

  messages.value.push({ role: 'user', content: displayContent, messageType: 'text' })
  uploadedFiles.value = []
  loading.value = true
  scrollToBottom()

  try {
    await sendThreadMessage(threadId, content, filePaths)
    // 更新侧边栏线程标题
    const thread = projectThreads.value.find(t => t.threadId === threadId)
    if (thread && thread.title === '新对话') {
      thread.title = content.length > 30 ? content.substring(0, 30) + '...' : content
    }
  } catch (e) {
    loading.value = false
    ElMessage.error('发送失败: ' + e.message)
  }
}

async function handleFileSelected(event) {
  const file = event.target.files?.[0]
  if (!file) return
  event.target.value = ''

  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件大小不能超过 10MB')
    return
  }

  // 上传需要 threadId；如果在欢迎页还没有线程，先创建一个
  let threadId = currentThreadId.value
  if (!threadId) {
    if (!selectedProject.value) {
      ElMessage.warning('请先选择一个项目')
      return
    }
    try {
      const res = await createThread(selectedProject.value.projectId)
      threadId = res.data.threadId
      currentThreadId.value = threadId
      if (expandedProjectId.value === selectedProject.value.projectId) {
        projectThreads.value.unshift(res.data)
      }
    } catch (e) {
      ElMessage.error('创建对话失败')
      return
    }
  }

  uploading.value = true
  try {
    const res = await uploadThreadFile(threadId, file)
    uploadedFiles.value.push({ name: file.name, path: res.data })
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.message || '未知错误'))
  } finally {
    uploading.value = false
  }
}

async function loadThreadHistory(threadId) {
  try {
    const res = await getThreadMessages(threadId)
    messages.value = (res.data || []).map(c => ({
      role: c.role,
      content: c.content,
      messageType: c.messageType
    }))
    scrollToBottom()
  } catch (e) {
    messages.value = []
  }
}

function connectWsForThread(threadId) {
  disconnectWebSocket()
  connectWebSocket(() => {
    subscribeThread(threadId, (msg) => {
      // 进度消息替换逻辑：如果最后一条也是 progress，则替换
      if (msg.messageType === 'progress') {
        const last = messages.value[messages.value.length - 1]
        if (last && last.messageType === 'progress') {
          last.content = msg.content
        } else {
          messages.value.push(msg)
        }
        if (msg.content?.includes('完成') || msg.content?.includes('完毕')) {
          loading.value = false
        }
      } else {
        const last = messages.value[messages.value.length - 1]
        // 完成类 progress 保留在 assistant 消息之前（作为分隔线）；其他 progress 删除
        if (last && last.messageType === 'progress') {
          if (!(last.content?.includes('完成') || last.content?.includes('完毕'))) {
            messages.value.pop()
          }
        }
        messages.value.push(msg)
        loading.value = false
      }
      scrollToBottom()
    })
  })
}

function renderMarkdown(content) {
  if (!content) return ''
  return md.render(content)
}

// 将 "回答完毕，耗时 9 秒" 格式化为 "已处理 9s"
function formatProgressText(content) {
  const match = content?.match(/耗时\s*(\d+)\s*秒/)
  if (match) {
    return `已处理 ${match[1]}s`
  }
  return content
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function autoResize() {
  nextTick(() => {
    const el = textareaRef.value || chatTextareaRef.value
    if (el) {
      el.style.height = 'auto'
      el.style.height = Math.min(el.scrollHeight, 120) + 'px'
    }
  })
}

watch(messages, () => scrollToBottom(), { deep: true })
</script>

<style scoped>
/* ========================
   工作台 — 类 Codex 风格
   遵循 DESIGN.md 设计规范
   ======================== */

.workbench {
  display: flex;
  height: 100%;
  background: #F9F9F9;
  font-family: -apple-system, system-ui, "Segoe UI", Roboto, "Helvetica Neue",
               "PingFang SC", "Noto Sans", "Noto Sans CJK SC",
               "Microsoft YaHei", 微软雅黑, sans-serif;
  color: #262626;
}

/* ---------- 左侧面板 ---------- */
.wb-sidebar {
  width: 280px;
  min-width: 280px;
  background: #fff;
  display: flex;
  flex-direction: column;
  padding: 12px 0;
}

.wb-sidebar-actions {
  padding: 0 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.wb-action-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.76);
  font-size: 14px;
  transition: background 0.15s;
}

.wb-action-item:hover {
  background: rgba(38, 38, 38, 0.04);
  color: #262626;
}

/* ---------- 项目区块 ---------- */
.wb-projects-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.wb-projects-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 8px;
}

.wb-projects-title {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  font-weight: 500;
  letter-spacing: 0.5px;
}

.wb-projects-actions {
  display: flex;
  gap: 4px;
}

.wb-icon-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  border-radius: 6px;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.36);
  transition: background 0.15s, color 0.15s;
}

.wb-icon-btn:hover {
  background: rgba(38, 38, 38, 0.06);
  color: #262626;
}

.wb-project-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
}

.wb-project-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.76);
  font-size: 14px;
  transition: background 0.15s;
}

.wb-project-item:hover {
  background: rgba(38, 38, 38, 0.04);
}

.wb-project-item.active {
  background: rgba(38, 38, 38, 0.06);
  color: #262626;
}

.wb-project-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 新增线程按钮 */
.wb-new-thread-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border: none;
  background: none;
  border-radius: 4px;
  color: rgba(38, 38, 38, 0.36);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s, color 0.15s;
  margin-left: auto;
  padding: 0;
}

.wb-project-item:hover .wb-new-thread-btn {
  opacity: 1;
}

.wb-new-thread-btn:hover {
  background: rgba(38, 38, 38, 0.08);
  color: #0091FF;
}

.wb-empty-hint {
  padding: 24px 12px;
  text-align: center;
  color: rgba(38, 38, 38, 0.36);
  font-size: 13px;
}

/* 展开箭头 */
.wb-expand-icon {
  transition: transform 0.15s;
  flex-shrink: 0;
  color: rgba(38, 38, 38, 0.36);
}

.wb-expand-icon.expanded {
  transform: rotate(90deg);
}

/* 线程列表 */
.wb-thread-list {
  padding: 2px 0 4px 22px;
}

.wb-thread-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.wb-thread-item:hover {
  background: rgba(38, 38, 38, 0.04);
}

.wb-thread-item.active {
  background: rgba(0, 145, 255, 0.06);
}

.wb-thread-title {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.76);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.wb-thread-item.active .wb-thread-title {
  color: #0074CC;
  font-weight: 500;
}

.wb-thread-time {
  font-size: 11px;
  color: rgba(38, 38, 38, 0.36);
  white-space: nowrap;
  flex-shrink: 0;
}

.wb-thread-empty {
  padding: 8px 12px;
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
}

/* ---------- 主内容区 ---------- */
.wb-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.wb-main-header {
  padding: 16px 24px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
}

.wb-main-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
  margin: 0;
}

/* ---------- 中心区域 ---------- */
.wb-main-center {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.wb-build-icon {
  color: #262626;
}

.wb-build-text {
  font-size: 24px;
  font-weight: 700;
  color: #262626;
}

.wb-project-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.76);
  font-size: 20px;
  font-weight: 500;
  transition: background 0.15s;
}

.wb-project-selector:hover {
  background: rgba(38, 38, 38, 0.04);
}

.wb-project-selector-wrapper {
  position: relative;
}

.wb-chevron-up {
  transform: rotate(180deg);
  transition: transform 0.2s;
}

.wb-project-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 50%;
  transform: translateX(-50%);
  min-width: 220px;
  max-height: 280px;
  overflow-y: auto;
  background: #fff;
  border-radius: 10px;
  box-shadow: rgba(31, 34, 37, 0.08) 0px 0px 0px 1px,
              rgba(0, 0, 0, 0.06) 0px 4px 12px 0px;
  padding: 4px;
  z-index: 100;
}

.wb-dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: rgba(38, 38, 38, 0.76);
  transition: background 0.15s;
}

.wb-dropdown-item:hover {
  background: rgba(38, 38, 38, 0.04);
}

.wb-dropdown-item.active {
  background: rgba(38, 38, 38, 0.06);
  color: #262626;
  font-weight: 500;
}

.wb-dropdown-empty {
  padding: 16px;
  text-align: center;
  color: rgba(38, 38, 38, 0.36);
  font-size: 13px;
}

/* ---------- 输入区 ---------- */
.wb-input-area {
  padding: 24px 32px 32px;
  display: flex;
  justify-content: center;
}

.wb-input-box {
  width: 100%;
  max-width: 860px;
  background: #fff;
  border: 1px solid rgba(38, 38, 38, 0.08);
  border-radius: 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  box-shadow: rgba(38, 38, 38, 0.1) 0px 1px 5px 0px;
  transition: border-color 0.2s;
}

.wb-input-box:focus-within {
  border-color: rgba(0, 145, 255, 0.4);
}

.wb-uploaded-files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 14px 0;
}

.wb-file-tag {
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

.wb-file-tag svg {
  color: rgba(38, 38, 38, 0.56);
  flex-shrink: 0;
}

.wb-file-name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-file-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: none;
  background: none;
  color: rgba(38, 38, 38, 0.28);
  cursor: pointer;
  font-size: 14px;
  padding: 0;
  line-height: 1;
  transition: color 0.15s;
}

.wb-file-remove:hover {
  color: rgba(38, 38, 38, 0.76);
}

.wb-textarea {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-size: 15px;
  font-family: inherit;
  color: #262626;
  line-height: 1.6;
  min-height: 48px;
  max-height: 160px;
  padding: 16px 20px 8px;
  background: transparent;
  box-sizing: border-box;
}

.wb-textarea::placeholder {
  color: rgba(38, 38, 38, 0.36);
}

/* 工具栏 */
.wb-input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px 12px;
}

.wb-toolbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
}

.wb-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.wb-toolbar-btn {
  width: 32px;
  height: 32px;
  border: none;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.36);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.wb-toolbar-btn:hover {
  background: rgba(38, 38, 38, 0.06);
  color: #262626;
}

.wb-add-btn {
  width: 36px;
  height: 36px;
  color: #262626;
}

.wb-send-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: rgba(38, 38, 38, 0.08);
  color: rgba(38, 38, 38, 0.36);
  cursor: not-allowed;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.wb-send-btn.active {
  background: #262626;
  color: #fff;
  cursor: pointer;
}

.wb-send-btn.active:hover {
  background: rgba(38, 38, 38, 0.85);
}

.wb-send-btn:disabled {
  background: rgba(38, 38, 38, 0.08);
  color: rgba(38, 38, 38, 0.36);
  cursor: not-allowed;
}

/* ========================
   聊天模式样式（参考 ChatGPT 风格）
   ======================== */

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 32px 0 16px;
  display: flex;
  flex-direction: column;
}

.chat-msg-row {
  max-width: 760px;
  width: 100%;
  margin: 0 auto;
  padding: 0 32px;
}

/* 用户消息：右上角圆角标签 */
.chat-user-msg {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 24px;
}

.chat-user-tag {
  display: inline-block;
  max-width: 70%;
  padding: 10px 16px;
  background: rgba(38, 38, 38, 0.05);
  border-radius: 18px;
  font-size: 15px;
  line-height: 1.5;
  color: #262626;
  word-break: break-word;
}

/* AI 消息：左对齐 Markdown */
.chat-ai-msg {
  margin-bottom: 24px;
}

.chat-ai-content {
  font-size: 15px;
  line-height: 1.7;
  color: #262626;
}

.chat-ai-content :deep(p) {
  margin: 8px 0;
}

.chat-ai-content :deep(p:first-child) {
  margin-top: 0;
}

.chat-ai-content :deep(ul),
.chat-ai-content :deep(ol) {
  padding-left: 24px;
  margin: 8px 0;
}

.chat-ai-content :deep(li) {
  margin: 4px 0;
}

.chat-ai-content :deep(pre) {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 16px;
  overflow-x: auto;
  margin: 12px 0;
}

.chat-ai-content :deep(pre code) {
  color: #d4d4d4;
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', Consolas, monospace;
}

.chat-ai-content :deep(code) {
  background: rgba(38, 38, 38, 0.06);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', Consolas, monospace;
}

.chat-ai-content :deep(pre code) {
  background: none;
  padding: 0;
}

.chat-ai-content :deep(blockquote) {
  border-left: 3px solid rgba(38, 38, 38, 0.12);
  margin: 12px 0;
  padding: 4px 16px;
  color: rgba(38, 38, 38, 0.66);
}

/* 进度消息 */
.chat-progress-msg {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 8px 0;
  color: rgba(38, 38, 38, 0.46);
  font-size: 13px;
}

/* 完成分隔线样式 */
.chat-progress-divider {
  display: flex;
  align-items: center;
  margin: 16px 0;
  gap: 12px;
}

.chat-progress-divider .divider-line {
  flex: 1;
  height: 1px;
  background: rgba(38, 38, 38, 0.15);
}

.chat-progress-divider .divider-text {
  flex-shrink: 0;
  color: rgba(38, 38, 38, 0.4);
  font-size: 13px;
  white-space: nowrap;
}

.chat-spinner {
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 加载指示器（三个点动画） */
.chat-loading {
  max-width: 760px;
  width: 100%;
  margin: 0 auto;
  padding: 0 32px 16px;
}

.chat-loading-dots {
  display: flex;
  gap: 6px;
  padding: 12px 0;
}

.chat-loading-dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(38, 38, 38, 0.2);
  animation: dotPulse 1.4s ease-in-out infinite;
}

.chat-loading-dots span:nth-child(2) {
  animation-delay: 0.2s;
}

.chat-loading-dots span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dotPulse {
  0%, 80%, 100% {
    opacity: 0.3;
    transform: scale(0.8);
  }
  40% {
    opacity: 1;
    transform: scale(1);
  }
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

/* 弹窗容器 */
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
