<template>
  <div class="layout">
    <AppSidebar :collapsed="collapsed" />
    <div class="main">
      <AppTopbar :collapsed="collapsed" @toggle="toggle" />
      <div class="content">
        <RouterView />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'
import AppSidebar from './AppSidebar.vue'
import AppTopbar from './AppTopbar.vue'

const STORAGE_KEY = 'ai-work-sidebar-collapsed'
const collapsed = ref(localStorage.getItem(STORAGE_KEY) === '1')

function toggle() {
  collapsed.value = !collapsed.value
  localStorage.setItem(STORAGE_KEY, collapsed.value ? '1' : '0')
}
</script>

<style scoped>
.layout {
  position: relative;
  height: 100vh;
  display: flex;
  overflow: hidden;
  background: var(--dc-canvas);
  color: var(--dc-ink);
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--dc-canvas);
}
.content {
  flex: 1;
  position: relative;
  overflow: auto;
  padding: 24px;
}
</style>
