import type { MenuTree } from '@/api/menu'

export interface SidebarItem {
  id: string
  name: string
  icon: string
  external: boolean
  path: string
  url: string
}

export interface SidebarGroup {
  id: string
  name: string
  items: SidebarItem[]
}

export interface SidebarModel {
  looseItems: SidebarItem[]
  groups: SidebarGroup[]
}

function isVisible(item: MenuTree): boolean {
  return String(item.menuType ?? '0') !== '1' && item.meta?.isHide !== true
}

function isExternal(item: MenuTree): boolean {
  return typeof item.path === 'string' && /^https?:\/\//.test(item.path)
}

function normalizePath(path: string): string {
  return path.startsWith('/') ? path : `/${path}`
}

const LEGACY_ICON_MAP: Record<string, string> = {
  'icon-yonghuguanli': 'members',
  'icon-bumenguanli': 'members',
  'icon-jiaoseguanli': 'roles',
  'icon-xitongrizhi': 'logs',
  'icon-jinridaiban': 'logs',
  'icon-caidan': 'apps',
  'icon-xitongguanli': 'settings',
  'icon-quanxianguanli': 'settings',
  'icon-zidianguanli': 'settings',
  'icon-canshuguanli': 'settings',
  'icon-gongju': 'settings',
  'icon-daimashengcheng': 'apps',
  'icon-shuju': 'datasets',
  'icon-shujuyuanguanli': 'datasets',
  'icon-shujubiaoguanli1': 'datasets',
  'icon-wendangguanli': 'kb',
  'icon-sensitiveword': 'label',
  'icon-lingpai': 'roles',
  'icon-anquanjiance': 'roles',
}

export function resolveMenuIcon(item: MenuTree): string {
  const rawIcon = (typeof item.icon === 'string' && item.icon.trim()) || item.meta?.icon || ''
  if (typeof rawIcon !== 'string' || !rawIcon.trim()) {
    return 'apps'
  }

  const parts = rawIcon.trim().split(/\s+/)
  const legacyKey = parts.find((part) => part.startsWith('icon-'))
  return legacyKey ? (LEGACY_ICON_MAP[legacyKey] ?? 'apps') : rawIcon.trim()
}

function toSidebarItem(item: MenuTree): SidebarItem {
  const rawPath = typeof item.path === 'string' ? item.path : ''
  const external = isExternal(item)

  return {
    id: String(item.id),
    name: item.name,
    icon: resolveMenuIcon(item),
    external,
    path: external ? '' : normalizePath(rawPath),
    url: external ? String(item.meta?.isLink || rawPath) : '',
  }
}

function isNavigable(item: MenuTree): boolean {
  return typeof item.path === 'string' && item.path.length > 0
}

export function buildSidebarModel(menus: MenuTree[]): SidebarModel {
  const looseItems: SidebarItem[] = []
  const groups: SidebarGroup[] = []

  for (const menu of menus) {
    if (!isVisible(menu)) {
      continue
    }

    const navigableChildren = Array.isArray(menu.children)
      ? menu.children.filter((item) => isVisible(item) && isNavigable(item))
      : []

    if (navigableChildren.length > 0) {
      groups.push({
        id: String(menu.id),
        name: menu.name,
        items: navigableChildren.map(toSidebarItem),
      })
      continue
    }

    if (isNavigable(menu)) {
      looseItems.push(toSidebarItem(menu))
    }
  }

  return { looseItems, groups }
}

export function flattenItems(model: SidebarModel): SidebarItem[] {
  return [...model.looseItems, ...model.groups.flatMap((group) => group.items)]
}
