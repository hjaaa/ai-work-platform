<template>
  <el-container class="h-screen">
    <el-aside width="200px" class="border-r border-gray-200">
      <div class="flex h-14 items-center justify-center text-lg font-bold">AI Work</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/home">首页</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="flex items-center border-b border-gray-200">AI Work Platform</el-header>
      <el-main>
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>
