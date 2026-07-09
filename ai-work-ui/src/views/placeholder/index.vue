<template>
  <div class="placeholder">
    <div class="ph-icon"><DcIcon :name="icon" :size="28" /></div>
    <div class="ph-title">{{ title }}</div>
    <div class="ph-desc">
      该模块的骨架位已就位。在此接入「{{ title }}」页面即可——推荐套用「搜索卡 + 表格卡 +
      弹窗」三件套。
    </div>
    <el-button type="primary" @click="goMembers">查看成员管理示例</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import DcIcon from '@/components/DcIcon.vue'
import { resolveMenuIcon } from '@/layout/menuNav'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const currentMenu = computed(() => {
  const walk = (list: typeof userStore.menus): (typeof userStore.menus)[number] | undefined => {
    for (const m of list) {
      const p = typeof m.path === 'string' ? (m.path.startsWith('/') ? m.path : `/${m.path}`) : ''
      if (p && p === route.path) return m
      if (Array.isArray(m.children)) {
        const found = walk(m.children)
        if (found) return found
      }
    }
    return undefined
  }
  return walk(userStore.menus)
})

const title = computed(() => {
  const metaTitle = route.meta?.title
  if (typeof metaTitle === 'string' && metaTitle) return metaTitle
  return currentMenu.value?.name || '未命名模块'
})

const icon = computed(() => {
  return currentMenu.value ? resolveMenuIcon(currentMenu.value) : 'apps'
})

function goMembers() {
  router.push('/members')
}
</script>

<style scoped>
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 520px;
  text-align: center;
}

.ph-icon {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: var(--dc-surface);
  box-shadow:
    0 0 0 1px var(--dc-ring),
    0 1px 4px 0 rgba(0, 0, 0, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary);
}

.ph-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--dc-ink);
}

.ph-desc {
  max-width: 400px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--dc-ink-subtle);
}
</style>
