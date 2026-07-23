<template>
  <div class="baas-detail">
    <div class="page-head">
      <div>
        <div class="page-title">
          {{ project?.name ?? projectRef }}
          <el-tag
            v-if="project"
            :type="PROJECT_STATUS_MAP[project.status].type"
            disable-transitions
            class="status-tag"
          >
            {{ PROJECT_STATUS_MAP[project.status].label }}
          </el-tag>
        </div>
        <div class="page-sub">ref:{{ projectRef }}</div>
      </div>
      <el-button @click="router.push('/baas/projects')">返回列表</el-button>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane label="概览" name="overview" />
      <el-tab-pane label="表" name="tables" />
      <el-tab-pane label="API Keys" name="keys" />
      <el-tab-pane label="用户" name="users" />
    </el-tabs>

    <template v-if="project">
      <OverviewTab
        v-if="activeTab === 'overview'"
        :ref-id="projectRef"
        :project="project"
        @refresh="loadProject"
      />
      <TablesTab v-else-if="activeTab === 'tables'" :ref-id="projectRef" />
      <KeysTab v-else-if="activeTab === 'keys'" :ref-id="projectRef" />
      <UsersTab v-else-if="activeTab === 'users'" :ref-id="projectRef" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProject } from '@/api/baas/project'
import type { ProjectVO } from '@/api/baas/types'
import { PROJECT_STATUS_MAP } from './statusMaps'
import OverviewTab from './components/OverviewTab.vue'
import TablesTab from './components/TablesTab.vue'
import KeysTab from './components/KeysTab.vue'
import UsersTab from './components/UsersTab.vue'

const route = useRoute()
const router = useRouter()
const projectRef = String(route.params.ref)
const project = ref<ProjectVO | null>(null)

const TAB_NAMES = ['overview', 'tables', 'keys', 'users']
const initialTab = typeof route.query.tab === 'string' && TAB_NAMES.includes(route.query.tab)
  ? route.query.tab
  : 'overview'
const activeTab = ref(initialTab)

// tab 入 URL query,可直达/可刷新保持
watch(activeTab, (tab) => {
  router.replace({ query: { ...route.query, tab } })
})

async function loadProject() {
  try {
    const res = await getProject(projectRef)
    project.value = res.data
  } catch {
    // 404(不存在或无权)已由拦截器 toast,回列表
    router.replace('/baas/projects')
  }
}

onMounted(loadProject)
</script>

<style scoped>
.baas-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
}
.page-title {
  font-size: 20px;
  font-weight: 500;
  color: var(--dc-ink);
  display: flex;
  align-items: center;
  gap: 10px;
}
.status-tag {
  font-weight: 400;
}
.page-sub {
  font-size: 14px;
  color: var(--dc-ink-subtle);
  margin-top: 4px;
}
.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
</style>
