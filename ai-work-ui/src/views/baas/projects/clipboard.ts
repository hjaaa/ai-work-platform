import { ElMessage } from 'element-plus'

// 复制到剪贴板并反馈;明文 key 仅显示一次的弹窗依赖此复制入口。
//
// Async Clipboard API 要求 secure context(HTTPS / localhost),而 §5 允许明文 HTTP 部署
// (http://gw:9999、http://host:9999)——那里 navigator.clipboard 为 undefined,单靠它会让
// 每一次复制都落到失败分支,一次性明文 key 因此可能直接丢失。故补 execCommand 降级路径。
export async function copyText(text: string): Promise<void> {
  if ((await writeViaClipboardApi(text)) || writeViaExecCommand(text)) {
    ElMessage.success('已复制')
    return
  }
  ElMessage.error('复制失败,请手动选择复制')
}

async function writeViaClipboardApi(text: string): Promise<boolean> {
  if (!navigator.clipboard) return false
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

// 非 secure context 下的兜底:临时 textarea + execCommand('copy')。
// execCommand 已废弃但在明文 HTTP 下仍是唯一可用的同步复制手段。
function writeViaExecCommand(text: string): boolean {
  const holder = document.createElement('textarea')
  holder.value = text
  holder.setAttribute('readonly', '')
  // 固定定位 + 透明,避免插入时页面滚动或闪烁
  holder.style.position = 'fixed'
  holder.style.top = '0'
  holder.style.opacity = '0'
  document.body.appendChild(holder)
  try {
    holder.select()
    holder.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(holder)
  }
}
