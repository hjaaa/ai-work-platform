<template>
  <div class="home">
    <div class="hero">
      <div class="hero-text">
        <div class="hero-title">{{ greeting }}，{{ name }}</div>
        <div class="hero-sub">{{ today }} · 今天有 {{ pendingTaskCount }} 个任务待处理</div>
      </div>
      <div class="hero-actions">
        <el-button @click="soon">自定义面板</el-button>
        <el-button type="primary" @click="soon">＋ 新建智能体</el-button>
      </div>
    </div>

    <div class="kpis">
      <div v-for="k in kpis" :key="k.label" class="dc-card-raised kpi">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value">{{ k.value }}</div>
        <div>
          <span class="kpi-delta" :class="k.pos ? 'pos' : 'neu'">{{ k.delta }}</span>
        </div>
      </div>
    </div>

    <div class="cols">
      <div class="dc-card panel">
        <div class="panel-head">
          <span class="panel-title">常用模块</span>
          <el-button link type="primary" @click="soon">管理</el-button>
        </div>
        <div class="modules">
          <button
            v-for="m in modules"
            :key="m.name"
            type="button"
            class="module dc-hover-fill"
            @click="go(m.path)"
          >
            <div class="module-icon"><DcIcon :name="m.icon" :size="20" /></div>
            <div class="module-name">{{ m.name }}</div>
            <div class="module-desc">{{ m.desc }}</div>
          </button>
        </div>
      </div>

      <div class="dc-card panel">
        <div class="panel-head tight">
          <span class="panel-title">最近动态</span>
          <el-button link type="primary" @click="soon">全部</el-button>
        </div>
        <div v-for="a in activities" :key="`${a.who}-${a.target}-${a.time}`" class="activity">
          <div class="activity-av" :style="{ background: a.color }">{{ a.av }}</div>
          <div class="activity-body">
            <div class="activity-line">
              <span class="activity-who">{{ a.who }}</span> {{ a.action }}
            </div>
            <div class="activity-target">{{ a.target }}</div>
            <div class="activity-time">{{ a.time }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import DcIcon from '@/components/DcIcon.vue'
import { useUserStore } from '@/stores/user'
import { formatToday, greetingForHour } from './greeting'
import { ACTIVITIES as activities, KPIS as kpis, MODULES as modules } from './mock'

const router = useRouter()
const userStore = useUserStore()

const now = new Date()
const greeting = greetingForHour(now.getHours())
const today = formatToday(now)
const pendingTaskCount = kpis.find((kpi) => kpi.label === '待处理任务')?.value ?? '0'
const name = computed(
  () =>
    userStore.userInfo?.nickname ||
    userStore.userInfo?.name ||
    userStore.userInfo?.username ||
    '用户',
)

function go(path: string) {
  router.push(path)
}

function soon() {
  ElMessage.info('该功能敬请期待')
}
</script>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 1400px;
}

.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.hero-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.hero-title {
  font-size: 24px;
  font-weight: 500;
  line-height: 1.33;
  color: var(--dc-ink);
}

.hero-sub {
  font-size: 14px;
  color: var(--dc-ink-subtle);
}

.hero-actions {
  display: flex;
  gap: 8px;
  flex: none;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kpi {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kpi-label {
  font-size: 14px;
  color: var(--dc-ink-subtle);
}

.kpi-value {
  font-size: 28px;
  font-weight: 500;
  line-height: 1.1;
  color: var(--dc-ink);
}

.kpi-delta {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
}

.kpi-delta.pos {
  background: var(--dc-success-tint);
  color: var(--dc-success);
}

.kpi-delta.neu {
  background: var(--dc-fill-2);
  color: var(--dc-ink-subtle);
}

.cols {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 16px;
  align-items: start;
}

.panel {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-head.tight {
  margin-bottom: 4px;
}

.panel-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--dc-ink);
}

.modules {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.module {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: none;
  border-radius: 10px;
  background: var(--dc-fill-1);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
}

.module-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: var(--dc-surface);
  box-shadow: 0 0 0 1px var(--dc-ring);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary);
}

.module-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--dc-ink);
}

.module-desc {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}

.activity {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--dc-hairline);
}

.activity-av {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 9999px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.activity-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.activity-line {
  font-size: 14px;
  color: var(--dc-ink-muted);
}

.activity-who {
  color: var(--dc-ink);
  font-weight: 500;
}

.activity-target {
  font-size: 14px;
  color: var(--dc-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.activity-time {
  font-size: 12px;
  color: var(--dc-ink-disabled);
}
</style>
