import { defineStore } from 'pinia'

// 登录联调接入后，在此补充 token、权限等字段与动作
export const useUserStore = defineStore('user', {
  state: () => ({
    username: '',
  }),
})
