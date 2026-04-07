<template>
  <div class="deploy-panel">
    <!-- 部署操作区 -->
    <div class="deploy-action">
      <el-select v-model="selectedServer" placeholder="选择目标服务器" style="width: 200px">
        <el-option v-for="s in servers" :key="s.name" :label="s.name + ' (' + s.host + ')'" :value="s.name" />
      </el-select>
      <el-button type="primary" @click="handleDeploy" :loading="deploying" :disabled="!selectedServer">
        <el-icon><Upload /></el-icon> 一键部署
      </el-button>
    </div>

    <!-- 部署进度 -->
    <div v-if="deploySteps.length > 0" class="deploy-progress">
      <el-steps :active="activeStep" finish-status="success" direction="vertical">
        <el-step v-for="(step, i) in deploySteps" :key="i"
                 :title="step.title"
                 :status="step.status"
                 :description="step.description" />
      </el-steps>
    </div>

    <!-- 部署结果 -->
    <div v-if="deployUrl" class="deploy-result">
      <el-alert type="success" :closable="false">
        <template #title>
          部署成功！访问地址：<a :href="deployUrl" target="_blank">{{ deployUrl }}</a>
        </template>
      </el-alert>
    </div>

    <el-empty v-if="!deploying && deploySteps.length === 0 && !deployUrl"
              description="选择服务器后点击「一键部署」" />
  </div>
</template>

<script setup>
import { ref, onMounted, defineProps } from 'vue'
import { listServers, triggerDeploy } from '../api/deploy'
import { ElMessage } from 'element-plus'

const props = defineProps({
  projectId: { type: String, required: true }
})

const servers = ref([])
const selectedServer = ref('')
const deploying = ref(false)
const activeStep = ref(0)
const deployUrl = ref('')
const deploySteps = ref([])

onMounted(async () => {
  try {
    const res = await listServers()
    servers.value = res.data || []
    if (servers.value.length === 1) {
      selectedServer.value = servers.value[0].name
    }
  } catch (e) {
    // 服务器列表加载失败不阻塞
  }
})

async function handleDeploy() {
  if (!selectedServer.value) return

  deploying.value = true
  deployUrl.value = ''
  deploySteps.value = [
    { title: '后端打包', status: 'process', description: 'mvn package...' },
    { title: '前端构建', status: 'wait', description: '' },
    { title: '构建 Docker 镜像', status: 'wait', description: '' },
    { title: '推送镜像', status: 'wait', description: '' },
    { title: '远程部署', status: 'wait', description: '' },
    { title: '健康检查', status: 'wait', description: '' }
  ]
  activeStep.value = 0

  try {
    await triggerDeploy(props.projectId, selectedServer.value)
    ElMessage.info('部署任务已启动，请关注进度')
  } catch (e) {
    ElMessage.error('部署启动失败: ' + e.message)
    deploying.value = false
  }
}

// 暴露方法供父组件调用（WebSocket 推送进度时更新步骤）
function updateProgress(message) {
  if (message.includes('后端') || message.includes('mvn')) {
    activeStep.value = 0
    deploySteps.value[0].status = 'process'
  } else if (message.includes('前端') || message.includes('npm')) {
    activeStep.value = 1
    deploySteps.value[0].status = 'success'
    deploySteps.value[1].status = 'process'
  } else if (message.includes('Docker 镜像') || message.includes('构建')) {
    activeStep.value = 2
    deploySteps.value[1].status = 'success'
    deploySteps.value[2].status = 'process'
  } else if (message.includes('推送')) {
    activeStep.value = 3
    deploySteps.value[2].status = 'success'
    deploySteps.value[3].status = 'process'
  } else if (message.includes('远程') || message.includes('部署到')) {
    activeStep.value = 4
    deploySteps.value[3].status = 'success'
    deploySteps.value[4].status = 'process'
  } else if (message.includes('健康检查')) {
    activeStep.value = 5
    deploySteps.value[4].status = 'success'
    deploySteps.value[5].status = 'process'
  } else if (message.includes('部署成功')) {
    activeStep.value = 6
    deploySteps.value[5].status = 'success'
    deploying.value = false
    // 提取 URL
    const match = message.match(/https?:\/\/[\S]+/)
    if (match) deployUrl.value = match[0]
  } else if (message.includes('失败') || message.includes('回滚')) {
    deploying.value = false
    const current = deploySteps.value.find(s => s.status === 'process')
    if (current) current.status = 'error'
  }
}

defineExpose({ updateProgress })
</script>

<style scoped>
.deploy-panel {
  padding: 16px;
}
.deploy-action {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}
.deploy-progress {
  margin: 16px 0;
}
.deploy-result {
  margin-top: 16px;
}
.deploy-result a {
  color: #409eff;
  text-decoration: underline;
}
</style>
