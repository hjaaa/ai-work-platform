<template>
  <div class="prd-preview">
    <template v-if="prdContent">
      <div class="prd-content" v-html="renderedPrd"></div>
      <div class="prd-actions">
        <el-button type="primary" @click="handleExport" :loading="exporting">
          <el-icon><Download /></el-icon> 下载 Word
        </el-button>
        <el-button type="success" @click="$emit('startDev')">
          <el-icon><VideoPlay /></el-icon> 基于此 PRD 开发
        </el-button>
      </div>
    </template>
    <el-empty v-else description="PRD 生成中，内容将在此实时展示..." />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import { ElMessage } from 'element-plus'

const md = new MarkdownIt()

const props = defineProps({
  prdContent: { type: String, default: '' },
  projectId: { type: String, required: true }
})
defineEmits(['startDev'])

const exporting = ref(false)

const renderedPrd = computed(() => {
  if (!props.prdContent) return ''
  return md.render(props.prdContent)
})

async function handleExport() {
  exporting.value = true
  try {
    // 通过 fetch 直接下载（因为响应是 blob）
    const response = await fetch(`/api/projects/${props.projectId}/prd/export?prdContent=${encodeURIComponent(props.prdContent)}`, {
      method: 'POST'
    })
    if (!response.ok) throw new Error('导出失败')

    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'PRD.docx'
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('PRD 导出成功')
  } catch (e) {
    ElMessage.error('导出失败: ' + e.message)
  } finally {
    exporting.value = false
  }
}
</script>

<style scoped>
.prd-preview {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.prd-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  font-size: 14px;
  line-height: 1.8;
}
.prd-content :deep(h1) {
  font-size: 20px;
  border-bottom: 1px solid #e4e7ed;
  padding-bottom: 8px;
}
.prd-content :deep(h2) {
  font-size: 17px;
  margin-top: 20px;
}
.prd-content :deep(h3) {
  font-size: 15px;
}
.prd-content :deep(ul), .prd-content :deep(ol) {
  padding-left: 20px;
}
.prd-actions {
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
