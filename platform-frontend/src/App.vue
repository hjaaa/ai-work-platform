<template>
  <el-container class="app-container">
    <el-header class="app-header">
      <div class="header-left">
        <span class="logo" @click="$router.push('/')">AI 工作平台</span>
      </div>
      <div class="header-right">
        <el-button text @click="$router.push('/')">
          <el-icon><List /></el-icon>
          项目列表
        </el-button>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
</script>

<style scoped>
.app-container {
  height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 20px;
}
.logo {
  font-size: 18px;
  font-weight: bold;
  cursor: pointer;
  color: #409eff;
}
.app-main {
  padding: 0;
  height: calc(100vh - 60px);
  overflow: hidden;
}
</style>
