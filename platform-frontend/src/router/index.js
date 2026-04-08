import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Workbench',
    component: () => import('../views/WorkbenchView.vue')
  },
  {
    path: '/projects',
    name: 'ProjectList',
    component: () => import('../views/ProjectListView.vue')
  },
  {
    path: '/project/:projectId',
    name: 'Chat',
    component: () => import('../views/ChatView.vue')
  },
  {
    path: '/project/:projectId/detail',
    name: 'ProjectDetail',
    component: () => import('../views/ProjectDetailView.vue')
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('../views/SettingsView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
