export interface Kpi {
  label: string
  value: string
  delta: string
  pos: boolean
}

export interface HomeModule {
  name: string
  icon: string
  desc: string
  path: string
}

export interface Activity {
  av: string
  color: string
  who: string
  action: string
  target: string
  time: string
}

export const KPIS: Kpi[] = [
  { label: '运行中的智能体', value: '12', delta: '+2 本周', pos: true },
  { label: '本月 API 调用', value: '1.28M', delta: '+18.4%', pos: true },
  { label: '待处理任务', value: '8', delta: '3 高优先级', pos: false },
  { label: '知识库文档', value: '3,240', delta: '+126 本周', pos: true },
]

export const MODULES: HomeModule[] = [
  { name: '智能体', icon: 'agents', desc: '12 个运行中', path: '/agents' },
  { name: '模型管理', icon: 'models', desc: '8 个模型', path: '/models' },
  { name: '提示词库', icon: 'prompts', desc: '156 条', path: '/prompts' },
  { name: '知识库', icon: 'kb', desc: '3,240 文档', path: '/kb' },
  { name: '任务中心', icon: 'tasks', desc: '8 待处理', path: '/tasks' },
  { name: '数据集', icon: 'datasets', desc: '42 个', path: '/datasets' },
  { name: '标注管理', icon: 'label', desc: '2 进行中', path: '/label' },
  { name: '成员管理', icon: 'members', desc: '28 人', path: '/members' },
  { name: '操作日志', icon: 'logs', desc: '今日 96', path: '/admin/log/index' },
]

export const ACTIVITIES: Activity[] = [
  { av: '李', who: '李文博', action: '部署了智能体', target: '客服助手 v2.3', time: '2 分钟前', color: '#0091ff' },
  { av: '系', who: '系统', action: '完成标注任务', target: '意图分类 · 0708 批次', time: '18 分钟前', color: '#00885b' },
  { av: '王', who: '王梦琪', action: '更新了提示词', target: '摘要生成 Prompt', time: '1 小时前', color: '#a1a4d9' },
  { av: '张', who: '张宇航', action: '新增知识库文档', target: '产品 FAQ（32 篇）', time: '今天 09:12', color: '#ff9a21' },
  { av: '赵', who: '赵启明', action: '触发了告警', target: '模型延迟 > 2s', time: '今天 08:40', color: '#d04934' },
  { av: '刘', who: '刘佳怡', action: '邀请了新成员', target: 'sun.yf@ai-platform.com', time: '昨天 17:22', color: '#0091ff' },
]
