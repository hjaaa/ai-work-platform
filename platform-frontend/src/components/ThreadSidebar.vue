<template>
  <div class="thread-sidebar">
    <div class="sidebar-header">
      <span class="sidebar-title">对话</span>
      <el-button type="primary" size="small" @click="$emit('create')">
        <el-icon><Plus /></el-icon> 新线程
      </el-button>
    </div>

    <div class="thread-list">
      <div
        v-for="thread in threads"
        :key="thread.threadId"
        :class="['thread-item', { 'thread-active': thread.threadId === currentThreadId }]"
        @click="$emit('select', thread.threadId)"
        @contextmenu.prevent="showContextMenu($event, thread)"
      >
        <div class="thread-title">{{ thread.title || '新对话' }}</div>
        <div class="thread-time">{{ formatTime(thread.updatedAt) }}</div>
      </div>

      <div v-if="threads.length === 0" class="thread-empty">
        暂无对话，点击上方按钮创建
      </div>
    </div>

    <!-- 右键菜单 -->
    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }"
    >
      <div class="context-menu-item" @click="handleRename">重命名</div>
      <div class="context-menu-item context-menu-danger" @click="handleDelete">删除</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'

const props = defineProps({
  threads: { type: Array, default: () => [] },
  currentThreadId: { type: String, default: null }
})

const emit = defineEmits(['select', 'create', 'rename', 'delete'])

const contextMenu = ref({ visible: false, x: 0, y: 0, thread: null })

function showContextMenu(event, thread) {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    thread
  }
}

function hideContextMenu() {
  contextMenu.value.visible = false
}

async function handleRename() {
  const thread = contextMenu.value.thread
  hideContextMenu()

  try {
    const { value } = await ElMessageBox.prompt('请输入新标题', '重命名', {
      inputValue: thread.title,
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    if (value && value.trim()) {
      emit('rename', thread.threadId, value.trim())
    }
  } catch {
    // 用户取消
  }
}

async function handleDelete() {
  const thread = contextMenu.value.thread
  hideContextMenu()

  try {
    await ElMessageBox.confirm(
      `确定删除「${thread.title || '新对话'}」？该操作不可恢复。`,
      '删除线程',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    emit('delete', thread.threadId)
  } catch {
    // 用户取消
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

onMounted(() => {
  document.addEventListener('click', hideContextMenu)
})

onUnmounted(() => {
  document.removeEventListener('click', hideContextMenu)
})
</script>

<style scoped>
.thread-sidebar {
  width: 240px;
  min-width: 240px;
  background: #ffffff;
  border-right: 1px solid rgba(38, 38, 38, 0.06);
  display: flex;
  flex-direction: column;
  height: 100%;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid rgba(38, 38, 38, 0.06);
}

.sidebar-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
}

.thread-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.thread-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.thread-item:hover {
  background: rgba(38, 38, 38, 0.04);
}

.thread-active {
  background: rgba(0, 145, 255, 0.06);
}

.thread-active:hover {
  background: rgba(0, 145, 255, 0.1);
}

.thread-title {
  font-size: 14px;
  color: #262626;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thread-active .thread-title {
  color: #0074CC;
  font-weight: 500;
}

.thread-time {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.36);
  margin-top: 2px;
}

.thread-empty {
  text-align: center;
  color: rgba(38, 38, 38, 0.36);
  font-size: 13px;
  padding: 32px 16px;
}

/* 右键菜单 */
.context-menu {
  position: fixed;
  background: #ffffff;
  border-radius: 8px;
  box-shadow: rgba(38, 38, 38, 0.12) 0px 4px 16px 0px;
  z-index: 1000;
  min-width: 120px;
  padding: 4px 0;
}

.context-menu-item {
  padding: 8px 16px;
  font-size: 14px;
  color: #262626;
  cursor: pointer;
  transition: background 0.15s;
}

.context-menu-item:hover {
  background: rgba(38, 38, 38, 0.04);
}

.context-menu-danger {
  color: #ff4d4f;
}
</style>
