<template>
  <template v-for="item in menus" :key="String(item.id)">
    <template v-if="isVisible(item)">
      <el-sub-menu v-if="hasVisibleChildren(item)" :index="String(item.id)">
        <template #title>{{ item.name }}</template>
        <MenuTreeNode :menus="item.children!" />
      </el-sub-menu>
      <el-menu-item
        v-else-if="isExternal(item)"
        :index="externalIndex(item)"
        class="external-menu-item"
        disabled
      >
        <a
          class="external-menu-link"
          :href="externalUrl(item)"
          target="_blank"
          rel="noopener noreferrer"
          @click.stop
          @keydown.space.prevent.stop="openExternal(item)"
        >
          {{ item.name }}
        </a>
      </el-menu-item>
      <el-menu-item v-else-if="item.path" :index="normalize(item.path)">
        {{ item.name }}
      </el-menu-item>
    </template>
  </template>
</template>

<script setup lang="ts">
import type { MenuTree } from '@/api/menu'

defineProps<{ menus: MenuTree[] }>()

// 后端 filterMenu 已排除按钮节点，这里的 menuType 判断仅作防御性兜底；
// meta.isHide 为后端下发的隐藏标记（如表单设计等隐藏页面），不渲染但不影响路由注册
function isVisible(item: MenuTree): boolean {
  return String(item.menuType ?? '0') !== '1' && item.meta?.isHide !== true
}

// 子节点全部隐藏/为按钮时不能渲染成空的可展开子菜单
function hasVisibleChildren(item: MenuTree): boolean {
  return Array.isArray(item.children) && item.children.some(isVisible)
}

function isExternal(item: MenuTree): boolean {
  return typeof item.path === 'string' && /^https?:\/\//.test(item.path)
}

function externalUrl(item: MenuTree): string {
  return item.meta?.isLink || String(item.path)
}

function externalIndex(item: MenuTree): string {
  return `external-${String(item.id)}`
}

function openExternal(item: MenuTree) {
  window.open(externalUrl(item), '_blank', 'noopener')
}

function normalize(path: string): string {
  return path.startsWith('/') ? path : `/${path}`
}
</script>

<style scoped>
:deep(.external-menu-item.is-disabled) {
  opacity: 1;
  cursor: default;
}

.external-menu-link {
  position: absolute;
  inset: 0;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  padding: inherit;
  color: inherit;
  text-decoration: none;
}
</style>
