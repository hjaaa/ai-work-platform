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

function toSidebarItem(item: MenuTree): SidebarItem {
  const rawPath = typeof item.path === 'string' ? item.path : ''
  const external = isExternal(item)

  return {
    id: String(item.id),
    name: item.name,
    icon: item.icon ?? '',
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
