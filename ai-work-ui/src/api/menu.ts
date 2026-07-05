import request from '@/utils/request'

// 后端 getNodeFunction 组装的 vue3 适配元数据（SysMenuServiceImpl），isLink 为外链 URL、isHide 为隐藏标记
export interface MenuMeta {
  title?: string
  isLink?: string
  isHide?: boolean
  isKeepAlive?: boolean
  isIframe?: boolean
  icon?: string
  [key: string]: unknown
}

// 后端 hutool Tree 序列化的树形菜单节点，扩展字段平铺在节点上；字段以联调实测为准
export interface MenuTree {
  id: number | string
  parentId: number | string
  name: string
  path?: string
  icon?: string
  menuType?: string | number // 0 菜单 / 1 按钮
  sortOrder?: number
  meta?: MenuMeta
  children?: MenuTree[]
  [key: string]: unknown
}

export function getUserMenu() {
  return request.get<MenuTree[]>('/admin/menu')
}
