import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import Layout from '@/layout/index.vue'
import { useUserStore } from '@/stores/user'
import type { MenuTree } from '@/api/menu'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/index.vue'),
    },
    {
      path: '/',
      name: 'layout',
      component: Layout,
      redirect: '/home',
      children: [
        {
          path: 'home',
          name: 'home',
          component: () => import('@/views/home/index.vue'),
        },
        {
          path: 'members',
          name: 'members',
          component: () => import('@/views/members/index.vue'),
          meta: { title: '成员管理' },
        },
      ],
    },
  ],
})

// 视图组件按约定映射：菜单 path=/xxx/yyy → src/views/xxx/yyy/index.vue
const viewModules = import.meta.glob('../views/**/index.vue')
const EXCLUDED_MENU_PATHS = new Set(['/home', '/members'])

function normalizeMenuPath(itemPath: string): string {
  const path = itemPath.startsWith('/') ? itemPath : `/${itemPath}`
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path
}

// 菜单树 → 路由表：仅为能匹配到视图文件的菜单项注册路由，按钮(menuType=1)跳过
function menusToRoutes(menus: MenuTree[]): RouteRecordRaw[] {
  const routes: RouteRecordRaw[] = []
  const walk = (items: MenuTree[]) => {
    for (const item of items) {
      if (String(item.menuType ?? '0') === '1') continue
      if (typeof item.path === 'string' && item.path) {
        const path = normalizeMenuPath(item.path)
        const view = viewModules[`../views${path}/index.vue`]
        if (view && !EXCLUDED_MENU_PATHS.has(path)) {
          routes.push({ path, name: `menu-${String(item.id)}`, component: view })
        }
      }
      if (Array.isArray(item.children) && item.children.length > 0) {
        walk(item.children)
      }
    }
  }
  walk(menus)
  return routes
}

const WHITE_LIST = ['/login']

router.beforeEach(async (to) => {
  const userStore = useUserStore()
  if (WHITE_LIST.includes(to.path)) {
    // 已登录访问登录页 → 回首页
    return userStore.isLoggedIn ? { path: '/' } : true
  }
  if (!userStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!userStore.loaded) {
    // 首次进入/刷新：拉取用户信息与菜单，注册动态路由后重进目标路由
    try {
      await userStore.fetchUserInfo()
      for (const record of menusToRoutes(userStore.menus)) {
        if (record.name && !router.hasRoute(record.name)) {
          router.addRoute('layout', record)
        }
      }
      // 兜底：菜单未实现对应页面时显示占位页
      if (!router.hasRoute('not-found')) {
        router.addRoute({
          path: '/:pathMatch(.*)*',
          name: 'not-found',
          component: () => import('@/views/placeholder/index.vue'),
        })
      }
      return { ...to, replace: true }
    } catch {
      // token 失效或接口异常：清登录态回登录页
      userStore.reset()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  return true
})

export default router
