<template>
  <div class="qr-overlay">
    <div class="qr-card" role="dialog" aria-modal="true" :aria-label="`${cfg.name}扫码登录`">
      <!-- 品牌头部：provider 渐变底色 -->
      <div class="qr-header" :style="{ background: cfg.headerGradient }">
        <div class="qr-header-main">
          <div class="qr-header-mark">
            <img :src="cfg.mark" :alt="cfg.name" />
          </div>
          <div>
            <div class="qr-header-title">{{ cfg.name }}扫码登录</div>
            <div class="qr-header-sub">安全 · 免密 · 快速接入</div>
          </div>
        </div>
        <button
          ref="closeBtnRef"
          type="button"
          class="qr-close"
          title="关闭"
          aria-label="关闭"
          @click="emit('close')"
        >
          <svg
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#fff"
            stroke-width="2.2"
            stroke-linecap="round"
          >
            <path d="M5 5l14 14M19 5L5 19"></path>
          </svg>
        </button>
      </div>

      <div class="qr-body">
        <!-- 钉钉:真实内嵌扫码 iframe;飞书:静态占位保持现状,待后续接入 -->
        <template v-if="provider === 'dingtalk'">
          <div v-if="sdkState === 'failed'" class="qr-box qr-box-dt qr-fallback">
            <p class="qr-fallback-text">扫码组件加载失败</p>
            <button type="button" class="qr-retry" @click="initDingTalkQr">重试</button>
          </div>
          <div v-else class="qr-box qr-box-dt">
            <div :id="DT_CONTAINER_ID" class="qr-dt-frame"></div>
            <div v-if="sdkState === 'loading' || submitting" class="qr-dt-mask">
              {{ submitting ? '登录中…' : '二维码加载中…' }}
            </div>
          </div>
        </template>
        <div v-else :key="qrKey" class="qr-box">
          <img :src="cfg.qr" alt="登录二维码" class="qr-img" />
          <div class="qr-center-mark">
            <img :src="cfg.mark" :alt="cfg.name" />
          </div>
          <div
            class="qr-scanline"
            :style="{ background: cfg.scanColor, boxShadow: `0 0 10px 2px ${cfg.scanGlow}` }"
          ></div>
        </div>

        <div class="qr-tip-title">请使用 {{ cfg.name }} 扫一扫</div>
        <div class="qr-tip-sub">
          打开 {{ cfg.name }} App → 扫一扫<br />扫描上方二维码即可安全登录
        </div>

        <div v-if="errorText" class="qr-pill qr-pill-error" role="alert">
          <span class="qr-pill-text">{{ errorText }}</span>
        </div>
        <div v-else class="qr-pill" :style="{ background: cfg.pillBg }">
          <span class="qr-pill-dot" :style="{ background: cfg.scanColor }"></span>
          <span class="qr-pill-text" :style="{ color: cfg.scanColor }"
            >二维码 2 分钟内有效，请尽快扫描</span
          >
        </div>

        <div class="qr-actions">
          <button type="button" class="qr-refresh" @click="refreshQr">
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M21 12a9 9 0 1 1-2.6-6.4M21 3v5h-5"></path>
            </svg>
            刷新二维码
          </button>
          <div class="qr-divider"></div>
          <button type="button" class="qr-back" @click="emit('close')">返回账号登录</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useUserStore } from '@/stores/user'
import { loadDingTalkSdk } from '@/utils/dingtalk'
import type { DTLoginSuccess } from '@/utils/dingtalk'
import dingtalkMark from '@/assets/dingtalk-mark.png'
import feishuMark from '@/assets/feishu-mark.png'
import qrDingtalk from '@/assets/qr-dingtalk.png'
import qrFeishu from '@/assets/qr-feishu.png'

const props = defineProps<{ provider: 'dingtalk' | 'feishu' }>()
const emit = defineEmits<{ close: []; success: [] }>()

const PROVIDERS = {
  dingtalk: {
    name: '钉钉',
    mark: dingtalkMark,
    qr: qrDingtalk,
    headerGradient: 'linear-gradient(135deg, #1a8cff, #37a9ff)',
    scanColor: '#1a8cff',
    scanGlow: 'rgba(26, 140, 255, 0.5)',
    pillBg: 'rgba(26, 140, 255, 0.08)',
  },
  feishu: {
    name: '飞书',
    mark: feishuMark,
    qr: qrFeishu,
    headerGradient: 'linear-gradient(135deg, #3370ff, #00c2b3)',
    scanColor: '#3370ff',
    scanGlow: 'rgba(51, 112, 255, 0.5)',
    pillBg: 'rgba(51, 112, 255, 0.08)',
  },
} as const

const cfg = computed(() => PROVIDERS[props.provider])

const userStore = useUserStore()

// 钉钉内嵌扫码:SDK 加载状态 / 换 token 提交锁 / 浮层内错误提示
const DT_CONTAINER_ID = 'dt-qr-login-frame'
const sdkState = ref<'loading' | 'ready' | 'failed'>('loading')
const submitting = ref(false)
const errorText = ref('')

const qrKey = ref(0)
const closeBtnRef = ref<HTMLButtonElement | null>(null)

async function initDingTalkQr() {
  sdkState.value = 'loading'
  try {
    await loadDingTalkSdk()
    const container = document.getElementById(DT_CONTAINER_ID)
    if (!container || !window.DTFrameLogin) throw new Error('钉钉扫码组件加载失败')
    // 二维码/authCode 一次性,重建 iframe 前清空容器
    container.innerHTML = ''
    window.DTFrameLogin(
      { id: DT_CONTAINER_ID, width: 280, height: 280 },
      {
        redirect_uri: encodeURIComponent(`${window.location.origin}/login`),
        client_id: import.meta.env.VITE_DINGTALK_APP_KEY,
        scope: 'openid',
        response_type: 'code',
        prompt: 'consent',
      },
      onScanSuccess,
      (errorMsg: string) => {
        errorText.value = errorMsg || '二维码加载失败，请刷新重试'
      },
    )
    sdkState.value = 'ready'
  } catch {
    sdkState.value = 'failed'
  }
}

async function onScanSuccess(result: DTLoginSuccess) {
  if (submitting.value) return
  submitting.value = true
  errorText.value = ''
  try {
    await userStore.socialLogin('DINGTALK', result.authCode)
    emit('success')
  } catch (e) {
    // 未绑定/凭证错等,优先展示后端 OAuth2 错误描述
    const data = (e as { response?: { data?: { msg?: string; error_description?: string } } })
      .response?.data
    errorText.value =
      data?.msg || data?.error_description || '该钉钉账号未绑定平台用户，请使用账号密码登录'
    // 旧二维码的 authCode 已被消费,自动重建供重扫
    void initDingTalkQr()
  } finally {
    submitting.value = false
  }
}

function refreshQr() {
  errorText.value = ''
  if (props.provider === 'dingtalk') void initDingTalkQr()
  else qrKey.value++
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => {
  closeBtnRef.value?.focus()
  document.addEventListener('keydown', onKeydown)
  if (props.provider === 'dingtalk') void initDingTalkQr()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
@keyframes qr-pop {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
@keyframes qr-scan {
  0% {
    top: 6%;
  }
  50% {
    top: 88%;
  }
  100% {
    top: 6%;
  }
}

.qr-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px;
}

.qr-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 18px;
  overflow: hidden;
  box-shadow:
    0 0 0 1px rgba(31, 34, 37, 0.06),
    0 30px 70px -28px rgba(24, 64, 130, 0.4);
  animation: qr-pop 0.32s cubic-bezier(0.2, 0.8, 0.2, 1);
}

/* ===== 品牌头部 ===== */
.qr-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
}
.qr-header-main {
  display: flex;
  align-items: center;
  gap: 11px;
}
.qr-header-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px -2px rgba(0, 0, 0, 0.18);
}
.qr-header-mark img {
  width: 22px;
  height: 22px;
  object-fit: contain;
  display: block;
}
.qr-header-title {
  font-size: 15px;
  font-weight: 500;
  color: #fff;
  line-height: 1.3;
}
.qr-header-sub {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
  line-height: 1.4;
}
.qr-close {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.16);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.qr-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

/* ===== 二维码区 ===== */
.qr-body {
  padding: 30px 32px 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.qr-box {
  position: relative;
  width: 196px;
  height: 196px;
  padding: 12px;
  border-radius: 14px;
  box-shadow:
    0 0 0 1px rgba(31, 34, 37, 0.08),
    0 10px 26px -14px rgba(24, 64, 130, 0.3);
  animation: qr-pop 0.32s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.qr-img {
  width: 100%;
  height: 100%;
  display: block;
  border-radius: 6px;
}
.qr-center-mark {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 38px;
  height: 38px;
  border-radius: 9px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px -2px rgba(0, 0, 0, 0.15);
}
.qr-center-mark img {
  width: 24px;
  height: 24px;
  object-fit: contain;
  display: block;
}
.qr-scanline {
  position: absolute;
  left: 12px;
  right: 12px;
  top: 6%;
  height: 2px;
  border-radius: 2px;
  animation: qr-scan 2.4s ease-in-out infinite;
}

/* ===== 钉钉内嵌扫码 ===== */
.qr-box-dt {
  width: auto;
  height: auto;
  padding: 8px;
}
.qr-dt-frame {
  width: 280px;
  height: 280px;
}
.qr-dt-mask {
  position: absolute;
  inset: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 6px;
  font-size: 13px;
  color: rgba(38, 38, 38, 0.6);
}
.qr-fallback {
  width: 296px;
  height: 296px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.qr-fallback-text {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.6);
  margin: 0;
}
.qr-retry {
  border: 1px solid var(--el-color-primary);
  border-radius: 8px;
  background: transparent;
  color: var(--el-color-primary);
  font-size: 13px;
  font-family: inherit;
  padding: 6px 18px;
  cursor: pointer;
  transition: background 0.2s;
}
.qr-retry:hover {
  background: rgba(26, 140, 255, 0.08);
}
.qr-pill-error {
  background: rgba(245, 63, 63, 0.08);
}
.qr-pill-error .qr-pill-text {
  color: #f53f3f;
}

/* ===== 文案与操作 ===== */
.qr-tip-title {
  font-size: 15px;
  font-weight: 500;
  color: #262626;
  margin-top: 22px;
}
.qr-tip-sub {
  font-size: 13px;
  color: rgba(38, 38, 38, 0.6);
  margin-top: 6px;
  text-align: center;
  line-height: 1.6;
}
.qr-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 7px 14px;
  border-radius: 20px;
}
.qr-pill-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.qr-pill-text {
  font-size: 12px;
}
.qr-actions {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-top: 22px;
}
.qr-refresh {
  display: flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: rgba(38, 38, 38, 0.6);
  font-family: inherit;
  padding: 0;
  transition: color 0.2s;
}
.qr-refresh:hover {
  color: var(--el-color-primary);
}
.qr-divider {
  width: 1px;
  height: 14px;
  background: rgba(38, 38, 38, 0.12);
}
.qr-back {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-color-primary);
  font-family: inherit;
  padding: 0;
  transition: color 0.2s;
}
.qr-back:hover {
  color: var(--el-color-primary-dark-2);
}

/* ===== 可访问性 ===== */
.qr-close:focus-visible,
.qr-refresh:focus-visible,
.qr-back:focus-visible,
.qr-retry:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .qr-card,
  .qr-box,
  .qr-scanline {
    animation: none;
  }
}
</style>
