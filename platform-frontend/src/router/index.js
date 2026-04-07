import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
