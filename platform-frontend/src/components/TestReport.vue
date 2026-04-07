<template>
  <div class="test-report">
    <template v-if="testResult">
      <div :class="['test-summary', testResult.success ? 'success' : 'failure']">
        <el-icon :size="24">
          <component :is="testResult.success ? 'CircleCheck' : 'CircleClose'" />
        </el-icon>
        <div class="summary-text">
          <div class="summary-title">{{ testResult.success ? '测试全部通过' : '测试有失败' }}</div>
          <div class="summary-detail">
            总计 {{ testResult.totalTests }} 个 |
            通过 {{ testResult.passedTests }} 个 |
            失败 {{ testResult.failedTests }} 个
            <span v-if="testResult.skippedTests"> | 跳过 {{ testResult.skippedTests }} 个</span>
          </div>
        </div>
      </div>

      <div v-if="testResult.failureDetails?.length > 0" class="failure-details">
        <div class="detail-title">失败详情</div>
        <div v-for="(detail, i) in testResult.failureDetails" :key="i" class="detail-item">
          {{ detail }}
        </div>
      </div>

      <div v-if="testResult.durationMs" class="test-duration">
        耗时: {{ (testResult.durationMs / 1000).toFixed(1) }}s
      </div>
    </template>
    <el-empty v-else description="暂无测试结果，代码生成后将自动执行测试" />
  </div>
</template>

<script setup>
defineProps({
  testResult: { type: Object, default: null }
})
</script>

<style scoped>
.test-report {
  padding: 16px;
}
.test-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 16px;
}
.test-summary.success {
  background: #f0f9eb;
  color: #67c23a;
}
.test-summary.failure {
  background: #fef0f0;
  color: #f56c6c;
}
.summary-title {
  font-size: 16px;
  font-weight: bold;
}
.summary-detail {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}
.failure-details {
  background: #fafafa;
  border-radius: 4px;
  padding: 12px;
  margin-bottom: 12px;
}
.detail-title {
  font-weight: bold;
  margin-bottom: 8px;
  font-size: 14px;
}
.detail-item {
  font-family: monospace;
  font-size: 12px;
  padding: 4px 0;
  color: #f56c6c;
  border-bottom: 1px solid #f0f0f0;
}
.test-duration {
  font-size: 12px;
  color: #909399;
}
</style>
