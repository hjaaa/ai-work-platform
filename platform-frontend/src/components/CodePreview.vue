<template>
  <div class="code-preview">
    <div class="file-tree" v-if="fileTree.length > 0">
      <div class="tree-header">项目文件</div>
      <div class="tree-content">
        <FileTreeNode
          v-for="node in fileTree"
          :key="node.path"
          :node="node"
          :depth="0"
          :selected-path="selectedPath"
          @select="handleFileSelect"
        />
      </div>
    </div>

    <div class="file-content" v-if="selectedPath">
      <div class="content-header">
        <span class="file-path">{{ selectedPath }}</span>
      </div>
      <div class="content-body">
        <pre v-if="fileContent !== null"><code v-html="highlightedCode"></code></pre>
        <el-empty v-else description="加载中..." />
      </div>
    </div>
    <div class="file-content empty" v-else>
      <el-empty description="点击左侧文件查看代码" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, defineProps } from 'vue'
import { getFileTree, getFileContent } from '../api/file'
import hljs from '../utils/hljs'
import FileTreeNode from './FileTreeNode.vue'

const props = defineProps({
  projectId: { type: String, required: true }
})

const fileTree = ref([])
const selectedPath = ref('')
const fileContent = ref(null)

async function loadFileTree() {
  try {
    const res = await getFileTree(props.projectId)
    fileTree.value = res.data || []
  } catch (e) {
    // 项目工作区可能尚未创建
  }
}

async function handleFileSelect(path) {
  selectedPath.value = path
  fileContent.value = null
  try {
    const res = await getFileContent(props.projectId, path)
    fileContent.value = res.data
  } catch (e) {
    fileContent.value = '// 无法加载文件内容'
  }
}

const highlightedCode = computed(() => {
  if (!fileContent.value) return ''
  const ext = selectedPath.value.split('.').pop()
  const langMap = {
    java: 'java', js: 'javascript', vue: 'vue', xml: 'xml',
    html: 'html', css: 'css', sql: 'sql', yml: 'yaml', yaml: 'yaml',
    json: 'json'
  }
  const lang = langMap[ext]
  if (lang) {
    try {
      return hljs.highlight(fileContent.value, { language: lang }).value
    } catch (e) {
      // 降级到纯文本
    }
  }
  return fileContent.value.replace(/</g, '&lt;').replace(/>/g, '&gt;')
})

watch(() => props.projectId, loadFileTree, { immediate: true })
</script>

<style scoped>
.code-preview {
  display: flex;
  height: 100%;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}
.file-tree {
  width: 240px;
  border-right: 1px solid #e4e7ed;
  overflow-y: auto;
}
.tree-header {
  padding: 8px 12px;
  font-weight: bold;
  font-size: 13px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}
.tree-content {
  padding: 4px 0;
}
.file-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.file-content.empty {
  justify-content: center;
  align-items: center;
}
.content-header {
  padding: 6px 12px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}
.file-path {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
}
.content-body {
  flex: 1;
  overflow: auto;
  padding: 0;
}
.content-body pre {
  margin: 0;
  padding: 12px;
  font-size: 13px;
  line-height: 1.5;
}
.content-body code {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}
</style>
