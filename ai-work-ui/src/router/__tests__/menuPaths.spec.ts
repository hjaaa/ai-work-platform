import { describe, expect, it } from 'vitest'
import type { MenuTree } from '@/api/menu'
import { collectMenuPaths } from '../menuPaths'

const MENUS: MenuTree[] = [
  {
    id: 3000,
    parentId: -1,
    name: 'AI 能力',
    path: '/ai',
    menuType: '0',
    children: [
      { id: 3001, parentId: 3000, name: '模型管理', path: '/models', menuType: '0' },
      { id: 3002, parentId: 3000, name: '尾斜杠', path: 'prompts/', menuType: '0' },
      { id: 3003, parentId: 3000, name: '按钮', path: '/models/add', menuType: '1' },
    ],
  },
  { id: 2100, parentId: 2000, name: '操作日志', path: '/admin/log/index', menuType: '0' },
  { id: 9999, parentId: -1, name: '无路径目录', menuType: '0' },
]

describe('collectMenuPaths', () => {
  it('递归收集全部层级的菜单路径并规范化', () => {
    const paths = collectMenuPaths(MENUS)
    expect(paths.has('/ai')).toBe(true)
    expect(paths.has('/models')).toBe(true)
    expect(paths.has('/prompts')).toBe(true)
    expect(paths.has('/admin/log/index')).toBe(true)
  })

  it('跳过按钮节点与无路径节点', () => {
    const paths = collectMenuPaths(MENUS)
    expect(paths.has('/models/add')).toBe(false)
    expect(paths.size).toBe(4)
  })

  it('空菜单树返回空集合', () => {
    expect(collectMenuPaths([]).size).toBe(0)
  })
})
