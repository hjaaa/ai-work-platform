import request from '@/utils/request'

export interface SysRole {
  roleId: number
  roleName: string
  roleCode: string
}

// 对应后端 UserInfo（extends UserVO 的扁平结构），字段以联调实测为准
export interface UserInfo {
  userId: number
  username: string
  nickname?: string
  name?: string
  avatar?: string
  phone?: string
  email?: string
  permissions: string[]
  roleList?: SysRole[]
  [key: string]: unknown
}

export function getUserInfo() {
  return request.get<UserInfo>('/admin/user/info')
}
