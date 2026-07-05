<template>
  <template v-for="item in menus" :key="String(item.id)">
    <template v-if="String(item.menuType ?? '0') !== '1'">
      <el-sub-menu v-if="hasChildren(item)" :index="String(item.id)">
        <template #title>{{ item.name }}</template>
        <MenuTreeNode :menus="item.children!" />
      </el-sub-menu>
      <el-menu-item v-else-if="item.path" :index="normalize(item.path)">
        {{ item.name }}
      </el-menu-item>
    </template>
  </template>
</template>

<script setup lang="ts">
import type { MenuTree } from '@/api/menu'

defineProps<{ menus: MenuTree[] }>()

function hasChildren(item: MenuTree): boolean {
  return Array.isArray(item.children) && item.children.length > 0
}

function normalize(path: string): string {
  return path.startsWith('/') ? path : `/${path}`
}
</script>
