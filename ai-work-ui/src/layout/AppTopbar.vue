<template>
  <header class="topbar">
    <div class="icon-btn" @click="emit('toggle')">
      <DcIcon name="panel" :size="18" />
    </div>
    <div class="crumb">
      <span class="crumb-root">AI 工作平台</span>
      <span class="crumb-sep">/</span>
      <span class="crumb-current">{{ title }}</span>
    </div>
    <div class="spacer"></div>

    <div class="search">
      <DcIcon name="search" :size="16" />
      <span class="search-ph">搜索模块、成员、任务…</span>
    </div>

    <div class="icon-btn bell">
      <DcIcon name="bell" :size="18" />
      <span class="dot"></span>
    </div>

    <el-dropdown trigger="click" @command="onCommand">
      <div class="user">
        <div class="avatar">{{ initial }}</div>
        <span class="user-name">{{ displayName }}</span>
        <DcIcon name="chevron" :size="16" />
      </div>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>
            <div class="user-head">
              <span class="user-head-name">{{ displayName }}</span>
              <span class="user-head-sub">{{ roleText }}</span>
            </div>
          </el-dropdown-item>
          <el-dropdown-item command="profile" divided>个人设置</el-dropdown-item>
          <el-dropdown-item command="team">切换团队</el-dropdown-item>
          <el-dropdown-item command="notify">通知设置</el-dropdown-item>
          <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import DcIcon from '@/components/DcIcon.vue'

defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ toggle: [] }>()

const route = useRoute()
const userStore = useUserStore()

const title = computed(() => {
  const metaTitle = route.meta?.title
  if (typeof metaTitle === 'string' && metaTitle) return metaTitle
  const hit = findMenuName(route.path)
  return hit || '工作台'
})

function findMenuName(path: string): string {
  const walk = (list: typeof userStore.menus): string => {
    for (const m of list) {
      const p = typeof m.path === 'string' ? (m.path.startsWith('/') ? m.path : `/${m.path}`) : ''
      if (p && p === path) return m.name
      if (Array.isArray(m.children)) {
        const found = walk(m.children)
        if (found) return found
      }
    }
    return ''
  }

  return walk(userStore.menus)
}

const displayName = computed(
  () => userStore.userInfo?.nickname || userStore.userInfo?.name || userStore.userInfo?.username || '用户',
)
const initial = computed(() => displayName.value.slice(0, 1))
const roleText = computed(() => {
  const role = userStore.userInfo?.roleList?.[0]?.roleName
  return role ? `${role}` : '成员'
})

function onCommand(command: string) {
  if (command === 'logout') {
    userStore.logout()
    return
  }

  ElMessage.info('该功能敬请期待')
}
</script>

<style scoped>
.topbar {
  height: 56px;
  flex: none;
  background: var(--dc-surface);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  box-shadow: inset 0 -1px 0 var(--dc-hairline);
  position: relative;
  z-index: 10;
}

.icon-btn {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--dc-ink-subtle);
  transition: background 0.15s;
}

.icon-btn:hover {
  background: var(--dc-fill-1);
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.crumb-root {
  color: var(--dc-ink-subtle);
}

.crumb-sep {
  color: var(--dc-ink-disabled);
}

.crumb-current {
  color: var(--dc-ink);
}

.spacer {
  flex: 1;
}

.search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 12px;
  border-radius: 8px;
  background: var(--dc-fill-1);
  width: 260px;
  color: var(--dc-ink-disabled);
}

.search-ph {
  font-size: 14px;
}

.bell {
  position: relative;
}

.dot {
  position: absolute;
  top: 8px;
  right: 9px;
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: var(--dc-error);
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 8px 0 4px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--dc-ink);
  transition: background 0.15s;
}

.user:hover {
  background: var(--dc-fill-1);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 9999px;
  background: var(--el-color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.user-name {
  font-size: 14px;
}

.user-head {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.user-head-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--dc-ink);
}

.user-head-sub {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}
</style>
