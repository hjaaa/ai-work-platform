import { describe, expect, it } from 'vitest'
import type { MenuTree } from '@/api/menu'
import { buildSidebarModel, flattenItems } from './menuNav'

const MENUS: MenuTree[] = [
  {
    id: 1,
    parentId: 0,
    name: 'AI 能力',
    menuType: '0',
    children: [
      { id: 11, parentId: 1, name: '模型管理', path: '/models', icon: 'models', menuType: '0' },
      { id: 12, parentId: 1, name: '隐藏页', path: '/hidden', menuType: '0', meta: { isHide: true } },
      { id: 13, parentId: 1, name: '按钮', menuType: '1' },
      { id: 14, parentId: 1, name: '外链', path: 'https://x.com', menuType: '0', meta: { isLink: 'https://x.com' } },
    ],
  },
  { id: 2, parentId: 0, name: '单页', path: 'reports', icon: 'logs', menuType: '0' },
  {
    id: 3,
    parentId: 0,
    name: '空组',
    menuType: '0',
    children: [{ id: 31, parentId: 3, name: '按钮', menuType: '1' }],
  },
]

describe('buildSidebarModel', () => {
  it('顶级含可见子项 → group，过滤按钮与隐藏项', () => {
    const model = buildSidebarModel(MENUS)
    const g = model.groups.find((x) => x.id === '1')!
    expect(g.name).toBe('AI 能力')
    expect(g.items.map((i) => i.id)).toEqual(['11', '14'])
  })

  it('内部项规范化 path，外链解析 url', () => {
    const model = buildSidebarModel(MENUS)
    const g = model.groups.find((x) => x.id === '1')!
    const models = g.items.find((i) => i.id === '11')!
    expect(models.path).toBe('/models')
    expect(models.external).toBe(false)
    const link = g.items.find((i) => i.id === '14')!
    expect(link.external).toBe(true)
    expect(link.url).toBe('https://x.com')
  })

  it('顶级叶子 → looseItem，path 补前导斜杠', () => {
    const model = buildSidebarModel(MENUS)
    expect(model.looseItems.map((i) => i.id)).toEqual(['2'])
    const firstLooseItem = model.looseItems[0]
    expect(firstLooseItem).toBeDefined()
    expect(firstLooseItem?.path).toBe('/reports')
  })

  it('全部子项不可见的组不产出', () => {
    const model = buildSidebarModel(MENUS)
    expect(model.groups.some((g) => g.id === '3')).toBe(false)
  })

  it('flattenItems 扁平化 looseItems + 所有 group items', () => {
    const model = buildSidebarModel(MENUS)
    expect(flattenItems(model).map((i) => i.id)).toEqual(['2', '11', '14'])
  })

  it('图标优先使用 item.icon，缺失时回退 meta.icon，并兼容旧 iconfont 名称', () => {
    const menus: MenuTree[] = [
      {
        id: 101,
        parentId: 0,
        name: '用户管理',
        path: '/admin/system/user/index',
        icon: '',
        menuType: '0',
        meta: { icon: 'iconfont icon-yonghuguanli' },
      },
      {
        id: 102,
        parentId: 0,
        name: '操作日志',
        path: '/admin/log/index',
        icon: 'iconfont icon-xitongrizhi',
        menuType: '0',
        meta: { icon: 'iconfont icon-yonghuguanli' },
      },
      {
        id: 103,
        parentId: 0,
        name: '未知图标',
        path: '/unknown-icon',
        menuType: '0',
        meta: { icon: 'iconfont icon-unknown' },
      },
    ]
    const model = buildSidebarModel(menus)
    expect(model.looseItems.map((item) => item.icon)).toEqual(['members', 'logs', 'apps'])
  })

  it('缺少 path 的可见节点不会进入 groups.items 或 looseItems', () => {
    const menus: MenuTree[] = [
      {
        id: 201,
        parentId: 0,
        name: '有缺失子节点',
        menuType: '0',
        children: [
          { id: 211, parentId: 201, name: '可见但缺 path', menuType: '0', meta: { isHide: false } },
          { id: 212, parentId: 201, name: '有路径子项', path: 'child', menuType: '0' },
          { id: 213, parentId: 201, name: '外链子项', path: 'https://example.com', menuType: '0', meta: { isLink: 'https://example.com' } },
        ],
      },
      { id: 202, parentId: 0, name: '缺少 path 的顶级', menuType: '0' },
      { id: 203, parentId: 0, name: '有路径的顶级', path: '/ok', menuType: '0' },
    ]

    const model = buildSidebarModel(menus)
    const group = model.groups.find((item) => item.id === '201')!
    expect(group.items.map((item) => item.id)).toEqual(['212', '213'])
    expect(model.looseItems.map((item) => item.id)).toEqual(['203'])
  })
})
