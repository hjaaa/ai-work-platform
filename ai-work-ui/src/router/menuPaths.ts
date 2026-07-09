export const EXCLUDED_MENU_PATHS = new Set(['/home', '/members'])

export function normalizeMenuPath(itemPath: string): string {
  const path = itemPath.startsWith('/') ? itemPath : `/${itemPath}`
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path
}
