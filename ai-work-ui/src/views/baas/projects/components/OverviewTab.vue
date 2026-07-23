<template>
  <div class="overview">
    <div class="dc-card section">
      <div class="section-title">基本信息</div>
      <el-descriptions :column="2">
        <el-descriptions-item label="名称">{{ project.name }}</el-descriptions-item>
        <el-descriptions-item label="ref">{{ project.projectRef }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ PROJECT_STATUS_MAP[project.status].label }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ project.createTime }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="dc-card section">
      <div class="section-title">CORS 配置(allowed_origins)</div>
      <!-- 通配/白名单二态显式切换(§7.7):开关开启=允许全部来源(提交 null);
           关闭=白名单(提交数组,空数组=拒绝全部浏览器来源,与 null 语义相反),不可退化为纯 tag 输入 -->
      <div class="origins-toggle">
        <el-switch v-model="allowAllOrigins" />
        <span class="toggle-label">允许全部来源(<code>*</code>)</span>
        <span class="toggle-hint">
          {{ allowAllOrigins
            ? '任意浏览器来源均可跨域访问数据面'
            : '仅下方白名单来源可跨域;白名单为空 = 拒绝全部浏览器来源' }}
        </span>
      </div>
      <div class="origins" :class="{ 'is-disabled': allowAllOrigins }">
        <el-tag
          v-for="origin in origins"
          :key="origin"
          :closable="!allowAllOrigins"
          disable-transitions
          @close="removeOrigin(origin)"
        >
          {{ origin }}
        </el-tag>
        <el-input
          v-model="originInput"
          placeholder="如 https://example.com,回车添加"
          style="width: 280px"
          :disabled="allowAllOrigins"
          @keyup.enter="addOrigin"
        />
      </div>
      <div class="section-actions">
        <el-button type="primary" :loading="savingOrigins" @click="saveOrigins">保存 CORS 配置</el-button>
      </div>
    </div>

    <div class="dc-card section">
      <div class="section-title">运维操作</div>
      <div class="op-row">
        <div>
          <div class="op-name">表结构对账</div>
          <div class="op-desc">比对平台元数据与项目库 information_schema,导入/修正/标记冲突</div>
        </div>
        <el-button :loading="reconciling" @click="onReconcile">触发对账</el-button>
      </div>
      <div class="op-row">
        <div>
          <div class="op-name">常规 JWT 轮换</div>
          <div class="op-desc">CURRENT → PREVIOUS(宽限至 access TTL),新签发用新密钥</div>
        </div>
        <el-button @click="onRotate">常规轮换</el-button>
      </div>
    </div>

    <div class="dc-card section danger-zone">
      <div class="section-title danger-title">危险操作</div>
      <div class="op-row">
        <div>
          <div class="op-name">紧急 JWT 轮换</div>
          <div class="op-desc">全部密钥立即 REVOKED,所有终端用户 access JWT 即刻失效</div>
        </div>
        <el-button type="danger" @click="onEmergencyRotate">紧急轮换</el-button>
      </div>
      <div class="op-row">
        <div>
          <div class="op-name">删除项目</div>
          <div class="op-desc">进入删除流程,延迟清理后数据不可恢复</div>
        </div>
        <el-button type="danger" @click="onDelete">删除项目</el-button>
      </div>
    </div>

    <!-- 对账报告 -->
    <el-dialog v-model="reportOpen" title="对账报告" width="640px">
      <template v-if="report">
        <div v-for="section in reportSections" :key="section.key" class="report-section">
          <div class="report-title">{{ section.label }}({{ section.items.length }})</div>
          <div v-if="section.items.length === 0" class="report-empty">无</div>
          <ul v-else class="report-list">
            <li v-for="(item, i) in section.items" :key="i">{{ item }}</li>
          </ul>
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="reportOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteProject,
  emergencyRotateJwtKey,
  patchProject,
  reconcileProject,
  rotateJwtKey,
} from '@/api/baas/project'
import type { ProjectVO, ReconcileReport } from '@/api/baas/types'
import { newOperationId } from '@/api/baas/base'
import { allowAllFromVO, corsPayload } from '../cors'
import { PROJECT_STATUS_MAP } from '../statusMaps'

const props = defineProps<{ refId: string; project: ProjectVO }>()
const emit = defineEmits<{ refresh: [] }>()
const router = useRouter()

// ===== allowed_origins(通配/白名单二态,§7.7)=====
// 后端 ProjectVO 回显:null=通配(允许全部来源)、[]=拒绝全部浏览器来源、非空数组=白名单。
// 二态语义相反,须显式区分,不可退化为纯 tag 输入,否则默认通配项目被静默存成 deny-all。
const allowAllOrigins = ref(allowAllFromVO(props.project.allowedOrigins))
const origins = ref<string[]>([...(props.project.allowedOrigins ?? [])])
watch(
  () => props.project.allowedOrigins,
  (v) => {
    allowAllOrigins.value = allowAllFromVO(v)
    origins.value = [...(v ?? [])]
  },
)
const originInput = ref('')
const savingOrigins = ref(false)

function addOrigin() {
  const v = originInput.value.trim()
  if (!v) return
  if (origins.value.includes(v)) {
    ElMessage.warning('该 origin 已存在')
    return
  }
  origins.value.push(v)
  originInput.value = ''
}

function removeOrigin(origin: string) {
  origins.value = origins.value.filter((o) => o !== origin)
}

async function saveOrigins() {
  savingOrigins.value = true
  try {
    // 通配开关开启 → 提交 null(后端 parseAllowedOrigins 仅 null 为通配);
    // 关闭 → 提交白名单数组(空数组 = [] = 拒绝全部浏览器来源,不塌成 null)
    await patchProject(props.refId, corsPayload(allowAllOrigins.value, origins.value))
    ElMessage.success('CORS 配置已保存')
    emit('refresh')
  } finally {
    savingOrigins.value = false
  }
}

// ===== 输入 ref 的强确认(紧急轮换/删除项目共用)=====
async function confirmByRef(title: string, warning: string): Promise<boolean> {
  try {
    await ElMessageBox.prompt(`${warning}。请输入项目 ref「${props.refId}」以确认:`, title, {
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      type: 'warning',
      inputValidator: (v: string) => v === props.refId || '输入与项目 ref 不一致',
    })
    return true
  } catch {
    return false
  }
}

// ===== 对账 =====
const reconciling = ref(false)
const report = ref<ReconcileReport | null>(null)
const reportOpen = ref(false)

const reportSections = computed(() => {
  const r = report.value
  if (!r) return []
  const fmt = (items: { tableName: string; reason: string }[]) =>
    items.map((i) => `${i.tableName}:${i.reason}`)
  return [
    { key: 'imported', label: '导入', items: r.imported },
    // corrected/recovered 后端为裸表名字符串数组(非 {tableName,reason}),不走 fmt
    { key: 'corrected', label: '修正', items: r.corrected },
    { key: 'recovered', label: '恢复', items: r.recovered },
    { key: 'conflicts', label: '冲突', items: fmt(r.conflicts) },
    { key: 'rejectedImports', label: '拒绝导入', items: fmt(r.rejectedImports) },
  ]
})

async function onReconcile() {
  try {
    await ElMessageBox.confirm('将触发一次表结构对账,期间涉及项目级 DDL 锁。继续?', '触发对账', {
      confirmButtonText: '触发',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  reconciling.value = true
  try {
    const res = await reconcileProject(props.refId, newOperationId())
    report.value = res.data
    reportOpen.value = true
  } finally {
    reconciling.value = false
  }
}

// ===== JWT 轮换 =====
async function onRotate() {
  try {
    await ElMessageBox.confirm(
      '常规轮换后新签发使用新密钥,旧 access JWT 在宽限期内仍有效。存在未过期 previous 密钥时会被拒绝。继续?',
      '常规 JWT 轮换',
      { confirmButtonText: '轮换', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const res = await rotateJwtKey(props.refId)
  ElMessage.success(`轮换完成,新 kid:${res.data.kid}`)
}

async function onEmergencyRotate() {
  if (!(await confirmByRef('紧急 JWT 轮换', '全部终端用户 access JWT 将立即失效,不可撤销'))) return
  const res = await emergencyRotateJwtKey(props.refId)
  ElMessage.success(`紧急轮换完成,新 kid:${res.data.kid}`)
}

// ===== 删除项目 =====
async function onDelete() {
  if (!(await confirmByRef('删除项目', '项目将进入删除流程,延迟清理后不可恢复'))) return
  await deleteProject(props.refId)
  ElMessage.success('项目已进入删除流程')
  router.replace('/baas/projects')
}
</script>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.section {
  padding: 20px;
}
.section-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--dc-ink);
  margin-bottom: 16px;
}
.danger-title {
  color: var(--dc-error);
}
.origins {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.section-actions {
  margin-top: 16px;
}
.op-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-top: 1px solid var(--dc-hairline);
}
.op-row:first-of-type {
  border-top: none;
}
.op-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--dc-ink);
}
.op-desc {
  font-size: 12px;
  color: var(--dc-ink-subtle);
  margin-top: 2px;
}
.report-section {
  margin-bottom: 12px;
}
.report-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--dc-ink);
}
.report-empty {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}
.report-list {
  margin: 4px 0 0 18px;
  font-size: 12px;
  color: var(--dc-ink-muted);
}
</style>
