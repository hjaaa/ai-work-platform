import request from '@/utils/request'

// 接口定义层写法示例，登录联调后替换为真实业务接口
export function fetchDemo() {
  return request.get('/admin/actuator/info')
}
