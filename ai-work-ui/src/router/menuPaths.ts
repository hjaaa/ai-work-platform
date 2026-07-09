export const EXCLUDED_MENU_PATHS = new Set(['/home', '/members'])

// 仅用于侧边栏隐藏：工作台由 AppSidebar 硬编码为首项，故从动态菜单隐藏；
// 成员管理需在侧边栏显示（路由去重仍由 EXCLUDED_MENU_PATHS 负责）。
export const SIDEBAR_HIDDEN_PATHS = new Set(['/home'])

export function normalizeMenuPath(itemPath: string): string {
  const path = itemPath.startsWith('/') ? itemPath : `/${itemPath}`
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path
}
