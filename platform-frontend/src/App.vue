<template>
  <div class="app-container">
    <!-- 左侧导航栏 -->
    <header class="sidebar">
      <!-- 侧边栏边缘分隔线 -->
      <div class="sidebar-edge"><div class="sidebar-edge-inner"></div></div>
      <!-- Logo -->
      <section class="sidebar-logo" @click="$router.push('/')">
        <div class="logo-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
            <rect x="3" y="3" width="7" height="18" rx="1.5" fill="#fff"/>
            <rect x="13" y="3" width="8" height="10" rx="1.5" fill="#fff"/>
          </svg>
        </div>
      </section>

      <!-- 主导航 -->
      <section class="sidebar-nav">
        <div
          v-for="item in mainNavItems"
          :key="item.path"
          class="nav-item"
          :class="{ active: activeMenu === item.path }"
          @click="$router.push(item.path)"
        >
          <div class="nav-icon" v-html="item.icon"></div>
          <span class="nav-label">{{ item.label }}</span>
        </div>
      </section>

      <!-- 底部区域 -->
      <section class="sidebar-bottom">
        <div class="sidebar-avatar" title="个人设置">
          <span>HJ</span>
        </div>
      </section>
    </header>

    <!-- 右侧内容区 -->
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => route.path)

// 主导航项 - SVG 图标
const mainNavItems = [
  {
    path: '/',
    label: '工作台',
    icon: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>
      <polyline points="9 22 9 12 15 12 15 22"/>
    </svg>`
  },
  {
    path: '/projects',
    label: '项目',
    icon: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <path d="M12 2L2 7l10 5 10-5-10-5z"/>
      <path d="M2 17l10 5 10-5"/>
      <path d="M2 12l10 5 10-5"/>
    </svg>`
  },
  {
    path: '/settings',
    label: '设置',
    icon: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="12" cy="12" r="3"/>
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>`
  }
]
</script>

<style scoped>
/* ========================
   Teambition Design System
   ======================== */

.app-container {
  display: flex;
  height: 100vh;
  background: #f9f9f9;
}

/* ---------- Sidebar ---------- */
.sidebar {
  width: 80px;
  min-width: 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  background: #f9f9f9;
  z-index: 100;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0;
  position: relative;
}

/* 侧边栏分隔线 - 参考 Teambition nav_bar-sidebar-divider */
.sidebar-edge {
  position: absolute;
  top: 8px;
  bottom: 8px;
  right: -8px;
  padding: 0 8px;
  opacity: 0;
  cursor: ew-resize;
  user-select: none;
  transition: opacity 0.5s;
  z-index: 101;
}

.sidebar:hover .sidebar-edge {
  opacity: 1;
}

.sidebar-edge-inner {
  width: 4px;
  height: 100%;
  background-color: rgba(0, 145, 255, 0.4);
  border-radius: 2px;
}

/* Logo */
.sidebar-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 60px;
  cursor: pointer;
}

.logo-icon {
  width: 28px;
  height: 28px;
  background: linear-gradient(180deg, #0089FF 0%, #6CBBFF 106%);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 搜索 & 新建 */
.sidebar-search {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 8px 0 4px;
}

.sidebar-icon-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.76);
  transition: background 0.2s, color 0.2s;
}

.sidebar-icon-btn:hover {
  background: rgba(38, 38, 38, 0.06);
  color: #262626;
}

.sidebar-create-btn {
  width: 28px;
  height: 28px;
  background: #0091FF;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s;
}

.sidebar-create-btn:hover {
  background: #0082E5;
}

/* 分隔线 */
.sidebar-divider {
  width: 20px;
  height: 1px;
  background: rgba(38, 38, 38, 0.09);
  margin: 8px 0;
}

/* 导航区域 */
.sidebar-nav {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 4px 0;
  width: 100%;
}

.sidebar-nav.secondary {
  flex-grow: 0;
}

/* 导航项 */
.nav-item {
  width: 64px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 0 6px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  color: rgba(38, 38, 38, 0.76);
}

.nav-item:hover {
  background: rgba(38, 38, 38, 0.04);
}

.nav-item.active {
  background: rgba(38, 38, 38, 0.06);
  color: #262626;
}

.nav-icon {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-label {
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

/* 底部 */
.sidebar-bottom {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 0 16px;
  position: sticky;
  bottom: 0;
  background: #f9f9f9;
}

.sidebar-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #f0a04b;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

/* ---------- Main Content ---------- */
.app-main {
  flex: 1;
  overflow-y: auto;
  background: #ffffff;
  margin: 8px 8px 8px 0;
  border-radius: 8px;
  box-shadow:
    rgba(31, 34, 47, 0.1) 0px 0px 0px 1px,
    rgba(0, 0, 0, 0.04) 0px 24px 48px 0px,
    rgba(0, 0, 0, 0.02) 0px 4px 16px 0px;
}
</style>
