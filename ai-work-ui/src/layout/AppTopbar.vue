<template>
  <header class="topbar">
    <button type="button" class="icon-btn" :aria-label="sidebarToggleLabel" @click="emit('toggle')">
      <DcIcon name="panel" :size="20" />
    </button>
    <div class="crumb">
      <span class="crumb-root">AI 工作平台</span>
      <span class="crumb-sep">/</span>
      <span class="crumb-current">{{ title }}</span>
    </div>
    <div class="spacer"></div>

    <div class="search">
      <DcIcon name="search" :size="18" />
      <span class="search-ph">搜索模块、成员、任务…</span>
    </div>

    <button type="button" class="icon-btn bell" aria-label="通知" @click="soon">
      <DcIcon name="bell" :size="20" />
      <span class="dot"></span>
    </button>

    <div class="user-menu">
      <button type="button" class="user" :aria-expanded="menuOpen" @click="toggleMenu">
        <div class="avatar">{{ initial }}</div>
        <span class="user-name">{{ displayName }}</span>
        <DcIcon name="chevron" :size="18" />
      </button>

      <template v-if="menuOpen">
        <div class="user-menu-overlay" @click="closeMenu"></div>
        <div class="user-menu-panel">
          <div class="user-menu-head">
            <div class="user-menu-avatar">{{ initial }}</div>
            <div class="user-menu-head-text">
              <span class="user-menu-head-name">{{ displayName }}</span>
              <span class="user-menu-head-sub">{{ roleText }}</span>
            </div>
          </div>
          <div class="user-menu-divider top"></div>
          <button type="button" class="user-menu-item" @click="select('profile')">个人设置</button>
          <button type="button" class="user-menu-item" @click="select('team')">切换团队</button>
          <button type="button" class="user-menu-item" @click="select('notify')">通知设置</button>
          <div class="user-menu-divider"></div>
          <button type="button" class="user-menu-item danger" @click="select('logout')">
            退出登录
          </button>
        </div>
      </template>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import DcIcon from '@/components/DcIcon.vue'

const props = defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ toggle: [] }>()

const route = useRoute()
const userStore = useUserStore()

const sidebarToggleLabel = computed(() => (props.collapsed ? '展开侧边栏' : '收起侧边栏'))

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

const menuOpen = ref(false)

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function closeMenu() {
  menuOpen.value = false
}

function select(command: string) {
  closeMenu()
  if (command === 'logout') {
    userStore.logout()
    return
  }

  soon()
}

function soon() {
  ElMessage.info('该功能敬请期待')
}
</script>

<style scoped>
.topbar {
  height: 72px;
  flex: none;
  background: var(--dc-surface);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 20px;
  box-shadow: inset 0 -1px 0 var(--dc-hairline);
  position: relative;
  z-index: 10;
}

.icon-btn {
  width: 40px;
  height: 40px;
  flex: none;
  border: none;
  border-radius: 8px;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--dc-ink-subtle);
  font-family: inherit;
  transition: background 0.15s;
}

.icon-btn:hover {
  background: var(--dc-fill-1);
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
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
  height: 44px;
  padding: 0 14px;
  border-radius: 8px;
  background: var(--dc-fill-1);
  width: 300px;
  color: var(--dc-ink-disabled);
}

.search-ph {
  font-size: 15px;
}

.bell {
  position: relative;
}

.dot {
  position: absolute;
  top: 9px;
  right: 10px;
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: var(--dc-error);
}

.user-menu {
  position: relative;
  flex: none;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 8px 0 4px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  color: var(--dc-ink);
  font-family: inherit;
  transition: background 0.15s;
}

.user:hover {
  background: var(--dc-fill-1);
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 9999px;
  background: var(--el-color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.user-name {
  font-size: 15px;
}

.user-menu-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.user-menu-panel {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 41;
  width: 212px;
  padding: 8px;
  border-radius: 12px;
  background: var(--dc-surface);
  box-shadow:
    0 0 0 1px var(--dc-ring),
    0 24px 48px rgba(0, 0, 0, 0.04),
    0 4px 16px rgba(0, 0, 0, 0.02);
}

.user-menu-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 10px;
}

.user-menu-avatar {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 9999px;
  background: var(--el-color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.user-menu-head-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-menu-head-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--dc-ink);
}

.user-menu-head-sub {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}

.user-menu-divider {
  height: 1px;
  background: var(--dc-hairline);
  margin: 6px 0;
}

.user-menu-divider.top {
  margin: 2px 0 6px;
}

.user-menu-item {
  display: flex;
  align-items: center;
  width: 100%;
  height: 36px;
  padding: 0 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--dc-ink);
  font-family: inherit;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.user-menu-item:hover {
  background: var(--dc-fill-1);
}

.user-menu-item.danger {
  color: var(--dc-error);
}
</style>
