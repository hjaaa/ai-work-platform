import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Router } from 'vue-router'
import type { UserInfo } from '@/api/user'
import type { MenuTree } from '@/api/menu'
import type { R } from '@/utils/request'

// 用 vi.hoisted 固定 mock 实例,使 resetModules 后重新执行工厂函数仍返回同一批 vi.fn
const mocks = vi.hoisted(() => ({
  getUserInfo: vi.fn(),
  getUserMenu: vi.fn(),
}))

vi.mock('@/api/user', () => ({ getUserInfo: mocks.getUserInfo }))
vi.mock('@/api/menu', () => ({ getUserMenu: mocks.getUserMenu }))
vi.mock('@/api/login', () => ({ login: vi.fn(), logout: vi.fn(), socialLogin: vi.fn() }))
// 路由守卫测试不渲染视图,组件一律用空壳替身,避免加载 element-plus 等重依赖
vi.mock('@/layout/index.vue', () => ({ default: { name: 'LayoutStub', render: () => null } }))
vi.mock('@/views/login/index.vue', () => ({ default: { name: 'LoginStub', render: () => null } }))
vi.mock('@/views/home/index.vue', () => ({ default: { name: 'HomeStub', render: () => null } }))
vi.mock('@/views/placeholder/index.vue', () => ({
  default: { name: 'PlaceholderStub', render: () => null },
}))
vi.mock('@/views/members/index.vue', () => ({
  default: { name: 'MembersStub', render: () => null },
}))

const ACCESS_TOKEN_KEY = 'ai-work-access-token'

function okR<T>(data: T): R<T> {
  return { code: 0, msg: '', data }
}

const USER_INFO: UserInfo = { userId: 1, username: 'admin', permissions: ['sys_user_view'] }

const MENUS: MenuTree[] = [
  { id: 1, parentId: 0, name: '首页', path: '/home', menuType: '0' },
  // 未实现对应视图文件的菜单,不应注册路由
  { id: 2, parentId: 0, name: '报表', path: '/report', menuType: '0' },
]

// router/index.ts 是模块级单例(含动态路由注册状态),每个用例重新加载隔离;
// pinia 也须从同一模块注册表中动态导入,否则 setActivePinia 作用不到 store
async function freshRouter(): Promise<Router> {
  vi.resetModules()
  const { createPinia, setActivePinia } = await import('pinia')
  setActivePinia(createPinia())
  const { default: router } = await import('../index')
  return router
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  // 清掉上一个 router 实例写入的 history 状态,避免新实例读到脏数据
  window.history.replaceState(null, '', '/')
  mocks.getUserInfo.mockResolvedValue(okR(USER_INFO))
  mocks.getUserMenu.mockResolvedValue(okR(MENUS))
})

describe('路由守卫', () => {
  it('未登录访问受保护路由,跳登录页并携带回跳地址', async () => {
    const router = await freshRouter()
    await router.push('/home')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/home')
    expect(mocks.getUserInfo).not.toHaveBeenCalled()
  })

  it('未登录访问登录页直接放行', async () => {
    const router = await freshRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(mocks.getUserInfo).not.toHaveBeenCalled()
  })

  it('已登录访问登录页,重定向回首页', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'token-1')
    const router = await freshRouter()
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/home')
  })

  it('已登录首次导航拉取用户信息并注册兜底路由', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'token-1')
    const router = await freshRouter()
    await router.push('/home')
    expect(router.currentRoute.value.path).toBe('/home')
    expect(mocks.getUserInfo).toHaveBeenCalledTimes(1)
    expect(mocks.getUserMenu).toHaveBeenCalledTimes(1)
    expect(router.hasRoute('not-found')).toBe(true)

    // 菜单未实现对应页面时命中 not-found 占位页,停在目标路径且不重复拉取用户信息
    await router.push('/report')
    expect(router.currentRoute.value.path).toBe('/report')
    expect(router.currentRoute.value.matched.some((r) => r.name === 'not-found')).toBe(true)
    expect(mocks.getUserInfo).toHaveBeenCalledTimes(1)
  })

  it('拉取用户信息失败时清登录态,回登录页并携带回跳地址', async () => {
    localStorage.setItem(ACCESS_TOKEN_KEY, 'token-1')
    mocks.getUserInfo.mockRejectedValue(new Error('token expired'))
    const router = await freshRouter()
    await router.push('/home')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/home')
    expect(localStorage.getItem(ACCESS_TOKEN_KEY)).toBeNull()
  })
})
