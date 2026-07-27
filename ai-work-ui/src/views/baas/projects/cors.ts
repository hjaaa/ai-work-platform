// allowed_origins 通配/白名单二态往返(§7.7)。后端 ProjectVO 回显:
// null=通配(允许全部来源)、[]=拒绝全部浏览器来源、非空数组=白名单。二态语义相反,
// 抽纯函数集中判定,防止默认通配项目被 tag 编辑器静默存成 deny-all。
export function allowAllFromVO(allowedOrigins: string[] | null): boolean {
  return allowedOrigins === null
}

export function corsPayload(allowAll: boolean, origins: string[]): string[] | null {
  return allowAll ? null : origins
}
