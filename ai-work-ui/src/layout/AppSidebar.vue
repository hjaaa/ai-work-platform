<template>
  <aside v-if="!collapsed" class="sidebar sidebar-expanded">
    <div class="brand">
      <BrandLogo :size="28" :svg-size="20" :radius="8" />
      <span class="brand-text">AI 工作平台</span>
    </div>
    <div class="nav">
      <button type="button" class="nav-item" :class="{ active: isActive('/home') }" @click="go('/home')">
        <DcIcon name="home" :size="18" />
        <span class="nav-item-label">工作台</span>
      </button>

      <button
        v-for="item in model.looseItems"
        :key="item.id"
        type="button"
        class="nav-item"
        :class="{ active: isActive(item.path) }"
        @click="clickItem(item)"
      >
        <DcIcon :name="item.icon" :size="18" />
        <span class="nav-item-label">{{ item.name }}</span>
      </button>

      <div v-for="group in model.groups" :key="group.id" class="group">
        <button
          type="button"
          class="group-head"
          :aria-expanded="openMap[group.id] !== false"
          @click="toggle(group.id)"
        >
          <span class="group-label">{{ group.name }}</span>
          <span
            class="group-chev"
            :style="{ transform: openMap[group.id] === false ? 'rotate(-90deg)' : 'rotate(0deg)' }"
          >
            <DcIcon name="chevron" :size="14" />
          </span>
        </button>
        <div v-show="openMap[group.id] !== false" class="group-items">
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            class="nav-item"
            :class="{ active: isActive(item.path) }"
            @click="clickItem(item)"
          >
            <DcIcon :name="item.icon" :size="18" />
            <span class="nav-item-label">{{ item.name }}</span>
          </button>
        </div>
      </div>
    </div>
  </aside>

  <aside v-else class="sidebar sidebar-rail">
    <div class="rail-brand">
      <BrandLogo :size="30" :svg-size="22" :radius="8" />
    </div>
    <div class="rail-nav">
      <button
        type="button"
        class="rail-item"
        :class="{ active: isActive('/home') }"
        title="工作台"
        aria-label="工作台"
        @click="go('/home')"
      >
        <DcIcon name="home" :size="20" />
      </button>
      <button
        v-for="item in flat"
        :key="item.id"
        type="button"
        class="rail-item"
        :class="{ active: isActive(item.path) }"
        :title="item.name"
        :aria-label="item.name"
        @click="clickItem(item)"
      >
        <DcIcon :name="item.icon" :size="20" />
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BrandLogo from '@/components/BrandLogo.vue'
import DcIcon from '@/components/DcIcon.vue'
import { useUserStore } from '@/stores/user'
import { buildSidebarModel, flattenItems, type SidebarItem } from './menuNav'

defineProps<{ collapsed: boolean }>()

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const model = computed(() => buildSidebarModel(userStore.menus))
const flat = computed(() => flattenItems(model.value))

const openMap = reactive<Record<string, boolean>>({})

function toggle(id: string) {
  openMap[id] = openMap[id] === false
}

function isActive(path: string): boolean {
  return path !== '' && route.path === path
}

function go(path: string) {
  if (route.path !== path) {
    router.push(path)
  }
}

function clickItem(item: SidebarItem) {
  if (item.external) {
    window.open(item.url, '_blank', 'noopener')
    return
  }

  go(item.path)
}
</script>

<style scoped>
.sidebar {
  display: flex;
  flex: none;
  flex-direction: column;
  height: 100%;
  background: var(--dc-canvas);
}

.sidebar-expanded {
  width: 220px;
}

.sidebar-rail {
  width: 64px;
}

.brand {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 14px;
}

.brand-text {
  color: var(--dc-ink);
  font-size: 16px;
  font-weight: 500;
}

.nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  overflow: auto;
  padding: 8px 8px 16px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 36px;
  padding: 0 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--dc-ink-muted);
  cursor: pointer;
  font-family: inherit;
  font-size: 14px;
  text-align: left;
  transition: background 0.15s;
}

.nav-item:hover {
  background: var(--dc-fill-1);
}

.nav-item.active {
  background: var(--dc-fill-2);
  color: var(--dc-ink);
}

.nav-item-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 8px;
}

.group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 28px;
  padding: 0 10px;
  border: none;
  background: transparent;
  color: var(--dc-ink-disabled);
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  user-select: none;
}

.group-label {
  flex: 1;
}

.group-chev {
  display: flex;
  width: 14px;
  height: 14px;
  transition: transform 0.15s;
}

.group-items {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rail-brand {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  height: 56px;
}

.rail-nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
  padding: 8px;
}

.rail-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin: 0 auto;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--dc-ink-muted);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s;
}

.rail-item:hover {
  background: var(--dc-fill-1);
}

.rail-item.active {
  background: var(--dc-fill-2);
  color: var(--dc-ink);
}
</style>
