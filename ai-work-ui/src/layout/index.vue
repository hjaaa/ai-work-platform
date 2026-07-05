<template>
  <el-container class="h-screen">
    <el-aside width="200px" class="border-r border-gray-200">
      <div class="flex h-14 items-center justify-center text-lg font-bold">AI Work</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/home">首页</el-menu-item>
        <MenuTreeNode :menus="userStore.menus" />
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="flex items-center justify-between border-b border-gray-200">
        <span>AI Work Platform</span>
        <el-dropdown @command="onCommand">
          <span class="cursor-pointer">
            {{ userStore.userInfo?.nickname || userStore.userInfo?.username || '未登录' }}
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main>
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MenuTreeNode from './MenuTreeNode.vue'

const userStore = useUserStore()

function onCommand(command: string) {
  if (command === 'logout') {
    userStore.logout()
  }
}
</script>
