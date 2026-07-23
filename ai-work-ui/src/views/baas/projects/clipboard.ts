import { ElMessage } from 'element-plus'

// 复制到剪贴板并反馈;明文 key 仅显示一次的弹窗依赖此复制入口
export async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败,请手动选择复制')
  }
}
