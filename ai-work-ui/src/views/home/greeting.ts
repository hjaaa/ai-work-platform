export function greetingForHour(hour: number): string {
  if (hour < 12) return '上午好'
  if (hour < 18) return '下午好'
  return '晚上好'
}

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

export function formatToday(d: Date): string {
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${WEEKDAYS[d.getDay()]}`
}
