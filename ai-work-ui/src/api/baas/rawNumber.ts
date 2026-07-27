// 精确 JSON 数值 token(用于列默认值 defaultValue)。
//
// 后端 ColumnDefinitionDTO.defaultValue 是 JsonNode(+PreciseJsonNodeDeserializer),只认
// 【JSON 数值 token】:DefaultValueRenderer.decimal 断言 isNumber()、integral 断言
// isIntegralNumber(),字符串 token 一律 400;而其值域按 BigDecimal/BigInteger 收,
// 覆盖 decimal(65,30) 与 int64 全域。
// JS 侧两条朴素路径都会失真:发字符串必被 400;先过 Number() 再 JSON.stringify 则
// 超 2^53 的整数被静默舍入(9007199254740993 → ...992,后端照单全收装错默认值)、
// 高精度 decimal 同样丢有效位。
// 故数值默认值用 RawNumber 承载用户输入原文,序列化时不经 Number,直接把原文写成裸数值 token。
export class RawNumber {
  readonly literal: string

  constructor(text: string) {
    this.literal = canonicalNumberLiteral(text)
  }
}

// 去整数部分前导零:JSON 不接受 007 / -007 这类 token,原样发出会让 axios 判定 body 非合法
// JSON 而把整串当普通字符串二次编码。BigInt 转换对任意长度整数部分都精确。
function canonicalNumberLiteral(text: string): string {
  const negative = text.startsWith('-')
  const digits = negative ? text.slice(1) : text
  const dot = digits.indexOf('.')
  const intPart = dot < 0 ? digits : digits.slice(0, dot)
  const frac = dot < 0 ? '' : digits.slice(dot)
  return `${negative ? '-' : ''}${BigInt(intPart).toString()}${frac}`
}

// nonce 每次随机,杜绝与用户输入(varchar 默认值、注释、表名)的文本碰撞
function randomNonce(): string {
  const bytes = new Uint8Array(8)
  crypto.getRandomValues(bytes)
  return `__rawnum_${Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')}__`
}

// JSON.stringify 无法直接输出裸 token:replacer 先把 RawNumber 换成带 nonce 的占位字符串,
// 再把「带引号的占位」整体替换回原文数值。
export function stringifyExact(body: unknown): string {
  const nonce = randomNonce()
  const json = JSON.stringify(body, (_key, value) =>
    value instanceof RawNumber ? `${nonce}${value.literal}${nonce}` : value,
  )
  return json.replace(new RegExp(`"${nonce}(-?\\d+(?:\\.\\d+)?)${nonce}"`, 'g'), '$1')
}

// 预序列化 body 交给 axios:data 为合法 JSON 字符串且显式声明 application/json 时,
// axios 默认 transformRequest 走 stringifySafely 原样透传,不会二次编码(axios 1.18)。
export const EXACT_JSON_HEADERS = { 'Content-Type': 'application/json' }
