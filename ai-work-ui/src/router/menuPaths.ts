import type { MenuTree } from '@/api/menu'

export const EXCLUDED_MENU_PATHS = new Set(['/home', '/members'])

// 仅用于侧边栏隐藏：工作台由 AppSidebar 硬编码为首项，故从动态菜单隐藏；
// 成员管理需在侧边栏显示（路由去重仍由 EXCLUDED_MENU_PATHS 负责）。
export const SIDEBAR_HIDDEN_PATHS = new Set(['/home'])

export function normalizeMenuPath(itemPath: string): string {
  const path = itemPath.startsWith('/') ? itemPath : `/${itemPath}`
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path
}

// 收集菜单树全部层级的可导航路径（跳过按钮节点），供首页快捷卡等入口按授权过滤
export function collectMenuPaths(menus: MenuTree[]): Set<string> {
  const paths = new Set<string>()
  const walk = (items: MenuTree[]) => {
    for (const item of items) {
      if (String(item.menuType ?? '0') !== '1' && typeof item.path === 'string' && item.path) {
        paths.add(normalizeMenuPath(item.path))
      }
      if (Array.isArray(item.children) && item.children.length > 0) {
        walk(item.children)
      }
    }
  }
  walk(menus)
  return paths
}
