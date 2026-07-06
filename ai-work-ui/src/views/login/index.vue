<template>
  <div class="login-page">
    <div class="blob blob-1" aria-hidden="true"></div>
    <div class="blob blob-2" aria-hidden="true"></div>
    <div class="blob blob-3" aria-hidden="true"></div>

    <div class="login-shell">
      <!-- 品牌面板 -->
      <div class="brand-panel">
        <div class="brand-blur brand-blur-1" aria-hidden="true"></div>
        <div class="brand-blur brand-blur-2" aria-hidden="true"></div>
        <div class="brand-body">
          <div class="brand-logo" aria-hidden="true">
            <svg ref="logoSvg" viewBox="0 0 100 100" width="44" height="44">
              <g stroke="#ffffff" stroke-width="2" stroke-linecap="round" opacity="0.42">
                <line data-edge="0-1" x1="28" y1="30" x2="72" y2="28"></line>
                <line data-edge="1-2" x1="72" y1="28" x2="52" y2="56"></line>
                <line data-edge="2-3" x1="52" y1="56" x2="74" y2="74"></line>
                <line data-edge="3-0" x1="74" y1="74" x2="28" y2="30"></line>
                <line data-edge="0-2" x1="28" y1="30" x2="52" y2="56"></line>
              </g>
              <g>
                <circle
                  data-pulse="0-1"
                  r="2.6"
                  fill="#ffffff"
                  cx="28"
                  cy="30"
                  opacity="0"
                ></circle>
                <circle
                  data-pulse="1-2"
                  r="2.6"
                  fill="#ffffff"
                  cx="72"
                  cy="28"
                  opacity="0"
                ></circle>
                <circle
                  data-pulse="2-3"
                  r="2.6"
                  fill="#dff0ff"
                  cx="52"
                  cy="56"
                  opacity="0"
                ></circle>
              </g>
              <g>
                <circle data-node="0" cx="28" cy="30" r="6.5" fill="#ffffff"></circle>
                <circle data-node="1" cx="72" cy="28" r="6" fill="#dff0ff"></circle>
                <circle data-node="2" cx="52" cy="56" r="7.5" fill="#ffffff"></circle>
                <circle data-node="3" cx="74" cy="74" r="6" fill="#dff0ff"></circle>
              </g>
            </svg>
          </div>
          <div>
            <div class="brand-name">AI Work Platform</div>
            <div class="brand-cn">智能工作平台</div>
          </div>
        </div>
      </div>

      <!-- 表单面板 -->
      <div class="form-panel">
        <div class="form-body">
          <h1 class="form-title">账号登录</h1>

          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            class="form-fields"
            hide-required-asterisk
            @submit.prevent="onSubmit"
          >
            <el-form-item prop="username" class="field">
              <label class="field-label" for="login-username">账号</label>
              <div class="field-control">
                <input
                  id="login-username"
                  v-model="form.username"
                  class="field-input"
                  placeholder="邮箱 / 手机号 / 用户名"
                  autocomplete="username"
                  @input="formRef?.clearValidate('username')"
                  @focus="focused = 'username'"
                  @blur="onUsernameBlur"
                />
                <div class="field-underline"></div>
                <div
                  class="field-underline-active"
                  :class="{
                    active: focused === 'username',
                  }"
                ></div>
              </div>
            </el-form-item>

            <el-form-item prop="password" class="field">
              <label class="field-label" for="login-password">密码</label>
              <div class="field-control">
                <input
                  id="login-password"
                  v-model="form.password"
                  class="field-input has-suffix"
                  :type="showPassword ? 'text' : 'password'"
                  placeholder="请输入密码"
                  autocomplete="current-password"
                  @input="formRef?.clearValidate('password')"
                  @focus="focused = 'password'"
                  @blur="focused = ''"
                />
                <button
                  type="button"
                  class="field-suffix-btn"
                  :title="showPassword ? '隐藏密码' : '显示密码'"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  @click="showPassword = !showPassword"
                >
                  <svg
                    v-if="showPassword"
                    width="18"
                    height="18"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.7"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  >
                    <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"></path>
                    <circle cx="12" cy="12" r="3"></circle>
                  </svg>
                  <svg
                    v-else
                    width="18"
                    height="18"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.7"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  >
                    <path
                      d="M2 12s3.5-7 10-7c2 0 3.8.6 5.3 1.5M22 12s-3.5 7-10 7c-2 0-3.8-.6-5.3-1.5"
                    ></path>
                    <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"></path>
                    <path d="M4 4l16 16"></path>
                  </svg>
                </button>
                <div class="field-underline"></div>
                <div
                  class="field-underline-active"
                  :class="{
                    active: focused === 'password',
                  }"
                ></div>
              </div>
            </el-form-item>

            <el-form-item v-if="captchaRequired" prop="code" class="field">
              <label class="field-label" for="login-code">验证码</label>
              <div class="field-control">
                <input
                  id="login-code"
                  v-model="form.code"
                  class="field-input has-captcha"
                  placeholder="请输入验证码"
                  autocomplete="off"
                  @input="formRef?.clearValidate('code')"
                  @focus="focused = 'code'"
                  @blur="focused = ''"
                />
                <img
                  :src="codeUrl"
                  alt="验证码，点击刷新"
                  class="field-captcha"
                  role="button"
                  tabindex="0"
                  @click="refreshCode"
                  @keydown.enter.prevent="refreshCode"
                  @keydown.space.prevent="refreshCode"
                />
                <div class="field-underline"></div>
                <div
                  class="field-underline-active"
                  :class="{ active: focused === 'code' }"
                ></div>
              </div>
            </el-form-item>

            <div class="options-row">
              <button type="button" class="remember" @click="remember = !remember">
                <span class="checkbox" :class="{ checked: remember }">
                  <span v-if="remember" class="checkmark"></span>
                </span>
                <span class="remember-text">记住我</span>
              </button>
              <button type="button" class="link">忘记密码？</button>
            </div>

            <button type="submit" class="submit-btn" :disabled="loading">
              <span v-if="loading" class="spinner" aria-hidden="true"></span>
              {{ loading ? '登录中…' : '登 录' }}
            </button>
          </el-form>

          <div class="alt-divider">
            <div class="alt-line"></div>
            <div class="alt-text">其他登录方式</div>
            <div class="alt-line"></div>
          </div>
          <div class="alt-icons">
            <button type="button" class="alt-btn" title="钉钉登录">
              <img :src="dingtalkMark" alt="钉钉" />
            </button>
            <button type="button" class="alt-btn" title="飞书登录">
              <img :src="feishuMark" alt="飞书" />
            </button>
          </div>

          <div class="signup-row">
            还没有账号？<button type="button" class="link">申请开通</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { checkCaptchaRequired, imageCodeUrl } from '@/api/login'
import { useUserStore } from '@/stores/user'
import dingtalkMark from '@/assets/dingtalk-mark.png'
import feishuMark from '@/assets/feishu-mark.png'

const REMEMBER_KEY = 'login-remember-username'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const codeUrl = ref('')
const showPassword = ref(false)
const remember = ref(true)
const focused = ref('')
const captchaRequired = ref(false)
const form = reactive({ username: '', password: '', code: '', randomStr: '' })
const rules = reactive<FormRules<typeof form>>({
  username: [{ required: true, message: '请输入账号', trigger: 'submit' }],
  password: [{ required: true, message: '请输入密码', trigger: 'submit' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'submit' }],
})

const savedUsername = localStorage.getItem(REMEMBER_KEY)
if (savedUsername) form.username = savedUsername

// 品牌 logo 动效：4 个节点在多套阵型间平滑换位，连线跟随、脉冲沿边流动
const logoSvg = ref<SVGSVGElement | null>(null)
const LOGO_FORMATIONS: [number, number][][] = [
  [
    [28, 30],
    [72, 28],
    [52, 56],
    [74, 74],
  ],
  [
    [30, 72],
    [74, 32],
    [34, 30],
    [70, 66],
  ],
  [
    [72, 70],
    [28, 66],
    [70, 32],
    [30, 28],
  ],
  [
    [50, 28],
    [74, 58],
    [50, 76],
    [26, 56],
  ],
]
const LOGO_BASE_R = [6.5, 6, 7.5, 6]
let logoRaf = 0

function logoSmoother(t: number) {
  return t * t * t * (t * (t * 6 - 15) + 10)
}

onMounted(() => {
  const svg = logoSvg.value
  if (!svg || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const nodes = [0, 1, 2, 3].map((i) => svg.querySelector<SVGCircleElement>(`[data-node="${i}"]`))
  const parseEnds = (el: SVGElement, key: 'edge' | 'pulse') => {
    const [a = 0, b = 0] = (el.dataset[key] ?? '').split('-').map(Number)
    return { el, a, b }
  }
  const edges = [...svg.querySelectorAll<SVGLineElement>('[data-edge]')].map((el) =>
    parseEnds(el, 'edge'),
  )
  const pulses = [...svg.querySelectorAll<SVGCircleElement>('[data-pulse]')].map((el) =>
    parseEnds(el, 'pulse'),
  )
  const start = performance.now()

  const loop = (now: number) => {
    const N = LOGO_FORMATIONS.length
    const travel = 1700
    const hold = 700
    const seg = travel + hold
    const elapsed = now - start
    const idx = Math.floor(elapsed / seg) % N
    const local = elapsed % seg
    const e = logoSmoother(Math.min(local / travel, 1))
    const A = LOGO_FORMATIONS[idx] ?? []
    const B = LOGO_FORMATIONS[(idx + 1) % N] ?? []
    const pos = A.map((p, i): [number, number] => {
      const q = B[i] ?? p
      return [p[0] + (q[0] - p[0]) * e, p[1] + (q[1] - p[1]) * e]
    })
    nodes.forEach((n, i) => {
      const p = pos[i]
      if (!n || !p) return
      n.setAttribute('cx', p[0].toFixed(2))
      n.setAttribute('cy', p[1].toFixed(2))
      n.setAttribute('r', ((LOGO_BASE_R[i] ?? 6) * (1 + 0.06 * Math.sin(now / 280 + i))).toFixed(2))
    })
    edges.forEach(({ el, a, b }) => {
      const pa = pos[a]
      const pb = pos[b]
      if (!pa || !pb) return
      el.setAttribute('x1', pa[0].toFixed(2))
      el.setAttribute('y1', pa[1].toFixed(2))
      el.setAttribute('x2', pb[0].toFixed(2))
      el.setAttribute('y2', pb[1].toFixed(2))
    })
    pulses.forEach(({ el, a, b }, k) => {
      const pa = pos[a]
      const pb = pos[b]
      if (!pa || !pb) return
      const u = (now / 1500 + k * 0.34) % 1
      el.setAttribute('cx', (pa[0] + (pb[0] - pa[0]) * u).toFixed(2))
      el.setAttribute('cy', (pa[1] + (pb[1] - pa[1]) * u).toFixed(2))
      el.setAttribute('opacity', Math.sin(Math.PI * u).toFixed(2))
    })
    logoRaf = requestAnimationFrame(loop)
  }
  logoRaf = requestAnimationFrame(loop)
})

onBeforeUnmount(() => {
  if (logoRaf) cancelAnimationFrame(logoRaf)
})

function refreshCode() {
  form.randomStr = `${Date.now()}${Math.floor(Math.random() * 1000)}`
  codeUrl.value = imageCodeUrl(form.randomStr)
  form.code = ''
  formRef.value?.clearValidate('code')
}

// 按需展示验证码：后端在同账号连续失败达到阈值后才要求验证码
async function syncCaptchaRequired() {
  const name = form.username.trim()
  if (!name) return
  const required = await checkCaptchaRequired(name)
  if (required && !captchaRequired.value) {
    captchaRequired.value = true
    refreshCode()
  } else if (!required && captchaRequired.value) {
    captchaRequired.value = false
    form.code = ''
    formRef.value?.clearValidate('code')
  }
}

function onUsernameBlur() {
  focused.value = ''
  void syncCaptchaRequired()
}

// 记住的账号回填后立即预检（刷新页面不丢“需要验证码”状态）
if (savedUsername) void syncCaptchaRequired()

async function validateForm() {
  form.username = form.username.trim()
  form.code = form.code.trim()
  if (!formRef.value) return false
  try {
    await formRef.value.validate()
    return true
  } catch {
    return false
  }
}

function getSafeRedirectPath(redirect: unknown) {
  if (typeof redirect !== 'string') return '/'
  if (!redirect.startsWith('/') || redirect.startsWith('//')) return '/'
  return redirect
}

async function onSubmit() {
  if (loading.value) return
  loading.value = true
  try {
    // 提交前确保预检完成：blur 预检可能未触发（密码管理器自动填充）或仍在途，
    // 否则达到阈值的账号会漏带验证码，正确密码也被后端拒绝
    form.username = form.username.trim()
    await syncCaptchaRequired()
    if (!(await validateForm())) return
    await userStore.login(form)
    if (remember.value) localStorage.setItem(REMEMBER_KEY, form.username)
    else localStorage.removeItem(REMEMBER_KEY)
    await router.push(getSafeRedirectPath(route.query.redirect))
  } catch (e) {
    // token 端点失败时优先展示后端消息（R.msg 或 OAuth2 error_description）
    const data = (e as { response?: { data?: { msg?: string; error_description?: string } } })
      .response?.data
    ElMessage.error(data?.msg || data?.error_description || '登录失败')
    // 失败后重新预检：新触发阈值时 syncCaptchaRequired 内部会展示并刷新验证码；
    // 已展示时这里刷新图片（旧验证码已被后端消费）
    const wasRequired = captchaRequired.value
    await syncCaptchaRequired()
    if (wasRequired && captchaRequired.value) refreshCode()
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ===== 页面地面：径向光斑 + 斜向渐变，漂浮模糊光斑 ===== */
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(1200px 620px at 14% 8%, #e7f0ff, transparent 60%),
    radial-gradient(1000px 720px at 92% 96%, #edeffb, transparent 55%),
    linear-gradient(135deg, #f1f7ff 0%, #f6f9fc 55%, #f9f9f9 100%);
}

@keyframes om-spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes om-float {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(0, -26px);
  }
}
@keyframes om-float2 {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(20px, 18px);
  }
}

.blob {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.blob-1 {
  top: -120px;
  left: -90px;
  width: 420px;
  height: 420px;
  background: rgba(0, 145, 255, 0.1);
  filter: blur(24px);
  animation: om-float 9s ease-in-out infinite;
}
.blob-2 {
  bottom: -140px;
  right: 6%;
  width: 460px;
  height: 460px;
  background: rgba(161, 164, 217, 0.18);
  filter: blur(26px);
  animation: om-float2 11s ease-in-out infinite;
}
.blob-3 {
  top: 16%;
  right: 24%;
  width: 180px;
  height: 180px;
  background: rgba(0, 145, 255, 0.06);
  filter: blur(18px);
  animation: om-float 13s ease-in-out infinite;
}

@media (prefers-reduced-motion: reduce) {
  .blob {
    animation: none;
  }
}

/* ===== 分栏卡片 ===== */
.login-shell {
  display: flex;
  width: min(940px, 94vw);
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  position: relative;
  z-index: 1;
  box-shadow:
    0 0 0 1px rgba(31, 34, 37, 0.06),
    0 40px 90px -36px rgba(24, 64, 130, 0.3),
    0 12px 32px -12px rgba(24, 64, 130, 0.1);
}

/* ===== 品牌面板 ===== */
.brand-panel {
  width: 400px;
  flex-shrink: 0;
  padding: 44px 40px;
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: linear-gradient(165deg, #eaf3ff 0%, #eef1fb 55%, #f4eefb 100%);
}
.brand-blur {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.brand-blur-1 {
  top: -70px;
  right: -60px;
  width: 220px;
  height: 220px;
  background: rgba(0, 145, 255, 0.14);
  filter: blur(20px);
}
.brand-blur-2 {
  bottom: 40px;
  left: -70px;
  width: 200px;
  height: 200px;
  background: rgba(161, 164, 217, 0.22);
  filter: blur(22px);
}
.brand-body {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 16px;
}
.brand-logo {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  overflow: hidden;
  background: linear-gradient(150deg, var(--el-color-primary), #37a9ff);
  box-shadow: 0 12px 26px -8px rgba(0, 145, 255, 0.55);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-name {
  font-size: 19px;
  font-weight: 500;
  color: #262626;
  line-height: 1.35;
}
.brand-cn {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.6);
  line-height: 1.5;
  margin-top: 2px;
}

/* ===== 表单面板 ===== */
.form-panel {
  flex: 1;
  min-width: 0;
  padding: 48px 52px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.form-body {
  width: 100%;
  max-width: 344px;
  margin: 0 auto;
}
.form-title {
  font-size: 20px;
  font-weight: 500;
  line-height: 1.4;
  color: #262626;
  margin: 0;
}
.form-fields {
  margin-top: 26px;
}

.field {
  margin-bottom: 22px;
}
.field:last-of-type {
  margin-bottom: 0;
}
.field :deep(.el-form-item__content) {
  display: block;
  line-height: normal;
}
.field :deep(.el-form-item__error) {
  position: static;
  padding-top: 6px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-color-danger);
}
.field-label {
  display: block;
  font-size: 12px;
  line-height: 1.67;
  color: rgba(38, 38, 38, 0.6);
  margin-bottom: 4px;
}
.field-control {
  position: relative;
}
.field-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: #262626;
  padding: 7px 0;
  font-family: inherit;
}
.field-input::placeholder {
  color: rgba(38, 38, 38, 0.4);
}
.field-input:-webkit-autofill {
  -webkit-box-shadow: 0 0 0 40px #fff inset;
  -webkit-text-fill-color: #262626;
}
.field-input.has-suffix {
  padding-right: 32px;
}
.field-input.has-captcha {
  padding-right: 108px;
}
.field-suffix-btn {
  position: absolute;
  right: 0;
  top: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  color: rgba(38, 38, 38, 0.45);
  padding: 6px 0 6px 8px;
  display: flex;
  align-items: center;
  transition: color 0.2s;
}
.field-suffix-btn:hover {
  color: var(--el-color-primary);
}
.field-captcha {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  height: 28px;
  width: 96px;
  cursor: pointer;
  border-radius: 4px;
  border: 1px solid rgba(38, 38, 38, 0.1);
}
.field-captcha:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.field-underline {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 1px;
  background: rgba(38, 38, 38, 0.1);
}
.field-underline-active {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--el-color-primary);
  transform: scaleX(0);
  transform-origin: left;
  transition:
    transform 0.28s cubic-bezier(0.4, 0, 0.2, 1),
    background 0.2s;
}
.field-underline-active.active {
  transform: scaleX(1);
}
.field.is-error .field-underline-active {
  transform: scaleX(1);
  background: var(--el-color-danger);
}

/* ===== 选项行 ===== */
.options-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20px;
}
.remember {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}
.checkbox {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
  background: #fff;
  box-shadow: inset 0 0 0 1.5px rgba(38, 38, 38, 0.25);
}
.checkbox.checked {
  background: var(--el-color-primary);
  box-shadow: none;
}
.checkmark {
  width: 9px;
  height: 5px;
  border-left: 2px solid #fff;
  border-bottom: 2px solid #fff;
  transform: rotate(-45deg);
  margin-top: -2px;
  display: block;
}
.remember-text {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.76);
}
.link {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-color-primary);
  font-family: inherit;
  padding: 0;
  transition: color 0.2s;
}
.link:hover {
  color: var(--el-color-primary-dark-2);
}

/* ===== 提交按钮 ===== */
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 22px;
  border: none;
  border-radius: 8px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 15px;
  font-family: inherit;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  box-shadow: 0 8px 18px -8px rgba(0, 145, 255, 0.6);
}
.submit-btn:hover {
  background: var(--el-color-primary-dark-2);
}
.submit-btn:disabled {
  cursor: default;
  opacity: 0.85;
}
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  display: inline-block;
  animation: om-spin 0.7s linear infinite;
  margin-right: 9px;
}

/* ===== 其他登录方式 ===== */
.alt-divider {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 26px;
}
.alt-line {
  flex: 1;
  height: 1px;
  background: rgba(38, 38, 38, 0.1);
}
.alt-text {
  font-size: 12px;
  color: rgba(38, 38, 38, 0.4);
}
.alt-icons {
  display: flex;
  justify-content: center;
  gap: 14px;
  margin-top: 18px;
}
.alt-btn {
  width: 44px;
  height: 44px;
  padding: 0;
  border-radius: 12px;
  border: none;
  background: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow:
    0 0 0 1px rgba(31, 34, 37, 0.08),
    0 1px 4px 0 rgba(0, 0, 0, 0.04);
  transition:
    background 0.2s,
    box-shadow 0.2s,
    transform 0.2s;
}
.alt-btn:hover {
  background: rgba(0, 145, 255, 0.1);
  box-shadow: 0 0 0 1px rgba(0, 145, 255, 0.4);
  transform: translateY(-2px);
}
.alt-btn img {
  width: 24px;
  height: 24px;
  object-fit: contain;
  display: block;
}

.signup-row {
  text-align: center;
  margin-top: 26px;
  font-size: 13px;
  color: rgba(38, 38, 38, 0.6);
}

/* ===== 可访问性与响应式 ===== */
.submit-btn:focus-visible,
.link:focus-visible,
.alt-btn:focus-visible,
.remember:focus-visible,
.field-suffix-btn:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

@media (max-width: 860px) {
  .brand-panel {
    display: none;
  }
  .form-panel {
    padding: 40px 28px;
  }
}
</style>
