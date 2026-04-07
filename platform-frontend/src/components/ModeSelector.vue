<template>
  <el-dialog v-model="visible" title="选择工作方式" width="520" :close-on-click-modal="false">
    <div class="mode-options">
      <div class="mode-card" @click="selectMode('chat')">
        <el-icon :size="32" color="#409eff"><ChatDotRound /></el-icon>
        <div class="mode-info">
          <div class="mode-title">直接描述需求开发</div>
          <div class="mode-desc">用自然语言描述需求，AI 直接生成代码</div>
        </div>
      </div>

      <div class="mode-card" @click="selectMode('prd')">
        <el-icon :size="32" color="#e6a23c"><EditPen /></el-icon>
        <div class="mode-info">
          <div class="mode-title">先生成 PRD</div>
          <div class="mode-desc">AI 帮你梳理需求，生成规范的 PRD 文档</div>
        </div>
      </div>

      <div class="mode-card" @click="selectMode('upload')">
        <el-icon :size="32" color="#67c23a"><UploadFilled /></el-icon>
        <div class="mode-info">
          <div class="mode-title">上传已有 PRD</div>
          <div class="mode-desc">上传 Word 文档或粘贴在线文档链接</div>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'select'])
const visible = ref(props.modelValue)

watch(() => props.modelValue, v => visible.value = v)
watch(visible, v => emit('update:modelValue', v))

function selectMode(mode) {
  visible.value = false
  emit('select', mode)
}
</script>

<style scoped>
.mode-options {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.mode-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}
.mode-card:hover {
  border-color: #409eff;
  background: #ecf5ff;
}
.mode-title {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 4px;
}
.mode-desc {
  font-size: 13px;
  color: #909399;
}
</style>
