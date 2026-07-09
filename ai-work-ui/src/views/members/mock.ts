export type MemberStatus = 'active' | 'invited' | 'disabled'

export interface Member {
  name: string
  email: string
  dept: string
  role: string
  status: MemberStatus
  last: string
  av: string
  color: string
}

export const STATUS_MAP: Record<
  MemberStatus,
  { label: string; type: 'success' | 'warning' | 'info' }
> = {
  active: { label: '活跃', type: 'success' },
  invited: { label: '待接受', type: 'warning' },
  disabled: { label: '已停用', type: 'info' },
}

export const MEMBERS: Member[] = [
  {
    name: '陈思远',
    email: 'chen.sy@ai-platform.com',
    dept: '算法平台',
    role: '管理员',
    status: 'active',
    last: '2 分钟前',
    av: '陈',
    color: '#0091ff',
  },
  {
    name: '李文博',
    email: 'li.wb@ai-platform.com',
    dept: '数据工程',
    role: '开发者',
    status: 'active',
    last: '今天 10:24',
    av: '李',
    color: '#00885b',
  },
  {
    name: '王梦琪',
    email: 'wang.mq@ai-platform.com',
    dept: '产品设计',
    role: '编辑者',
    status: 'invited',
    last: '—',
    av: '王',
    color: '#a1a4d9',
  },
  {
    name: '张宇航',
    email: 'zhang.yh@ai-platform.com',
    dept: '算法平台',
    role: '开发者',
    status: 'active',
    last: '今天 09:12',
    av: '张',
    color: '#ff9a21',
  },
  {
    name: '刘佳怡',
    email: 'liu.jy@ai-platform.com',
    dept: '运营支持',
    role: '只读',
    status: 'disabled',
    last: '3 天前',
    av: '刘',
    color: 'rgba(38,38,38,0.4)',
  },
  {
    name: '赵启明',
    email: 'zhao.qm@ai-platform.com',
    dept: '数据工程',
    role: '开发者',
    status: 'active',
    last: '昨天 20:05',
    av: '赵',
    color: '#0091ff',
  },
  {
    name: '孙一凡',
    email: 'sun.yf@ai-platform.com',
    dept: '产品设计',
    role: '编辑者',
    status: 'invited',
    last: '—',
    av: '孙',
    color: '#00885b',
  },
]
