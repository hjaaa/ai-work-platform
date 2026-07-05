import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi } from '@/api/login'
import type { LoginForm } from '@/api/login'
import { getUserInfo } from '@/api/user'
import type { UserInfo } from '@/api/user'
import { getUserMenu } from '@/api/menu'
import type { MenuTree } from '@/api/menu'
import { getAccessToken, setTokens, clearTokens } from '@/utils/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: getAccessToken() || '',
    userInfo: null as UserInfo | null,
    permissions: [] as string[],
    menus: [] as MenuTree[],
    // 用户信息与菜单是否已拉取（路由守卫据此决定是否触发 fetchUserInfo）
    loaded: false,
  }),
  getters: {
    isLoggedIn: (state) => !!state.accessToken,
  },
  actions: {
    async login(form: LoginForm) {
      const res = await loginApi(form)
      this.accessToken = res.access_token
      setTokens(res.access_token, res.refresh_token)
    },
    async fetchUserInfo() {
      const [infoRes, menuRes] = await Promise.all([getUserInfo(), getUserMenu()])
      this.userInfo = infoRes.data
      this.permissions = infoRes.data.permissions ?? []
      this.menus = menuRes.data ?? []
      this.loaded = true
    },
    async logout() {
      try {
        await logoutApi()
      } catch {
        // 登出接口失败也继续本地登出兜底
      }
      this.reset()
      // 整页跳转，顺带重置动态路由与内存状态
      window.location.href = '/login'
    },
    reset() {
      clearTokens()
      this.accessToken = ''
      this.userInfo = null
      this.permissions = []
      this.menus = []
      this.loaded = false
    },
  },
})
