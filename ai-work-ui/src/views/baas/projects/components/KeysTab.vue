<template>
  <div class="keys-tab">
    <div class="dc-card">
      <div class="table-head">
        <div class="table-title">API Keys</div>
        <div class="head-actions">
          <span v-if="!projectActive" class="tombstone">项目非可用状态,创建 Key 已停用</span>
          <el-button @click="loadKeys">刷新</el-button>
          <el-button v-if="projectActive" type="primary" @click="createOpen = true">
            ＋ 创建 Key
          </el-button>
        </div>
      </div>
      <el-table v-loading="loading" :data="keys" style="width: 100%">
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.keyType === 'SECRET' ? 'danger' : 'primary'" disable-transitions>
              {{ row.keyType === 'SECRET' ? 'secret' : 'publishable' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Key" min-width="200">
          <template #default="{ row }">
            <!-- 仅展示创建时留存的 12 字符前缀,secret 恒不回显明文 -->
            <code>{{ row.keyPrefix }}…</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" disable-transitions>
              {{ row.status === 'ACTIVE' ? '有效' : '已吊销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE'"
              link
              type="danger"
              @click="onRevoke(row)"
            >
              吊销
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createOpen" title="创建 API Key" width="480px">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="类型">
          <el-radio-group v-model="createType">
            <el-radio value="PUBLISHABLE">publishable(前端可公开,受 ACL 约束)</el-radio>
            <el-radio value="SECRET">secret(仅服务端持有,绕过 ACL)</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="plaintextOpen"
      title="Key 明文(仅显示一次)"
      width="560px"
      :close-on-click-modal="false"
      @close="closePlaintext"
    >
      <el-alert type="warning" :closable="false" show-icon title="请立即保存,关闭后将无法再次查看明文" />
      <div v-if="created" class="key-row">
        <code class="key-value">{{ created.plaintext }}</code>
        <el-button link type="primary" @click="copyText(created.plaintext)">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="plaintextOpen = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createKey, listKeys, revokeKey } from '@/api/baas/apiKey'
import type { ApiKeyVO, CreatedKeyVO, KeyType, ProjectStatus } from '@/api/baas/types'
import { copyText } from '../clipboard'

const props = defineProps<{ refId: string; projectStatus: ProjectStatus }>()

// ProjectKeyService.lockActiveProject 对非 ACTIVE 项目直接抛 ProjectNotFoundException,
// 建 Key 会以「项目不存在」这类误导性错误失败,故项目不可用时不呈现创建入口。
const projectActive = computed(() => props.projectStatus === 'ACTIVE')

const keys = ref<ApiKeyVO[]>([])
const loading = ref(false)
const createOpen = ref(false)
const createType = ref<KeyType>('PUBLISHABLE')
const creating = ref(false)
const plaintextOpen = ref(false)
const created = ref<CreatedKeyVO | null>(null)

async function loadKeys() {
  loading.value = true
  try {
    const res = await listKeys(props.refId)
    keys.value = res.data ?? []
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  creating.value = true
  try {
    const res = await createKey(props.refId, createType.value)
    createOpen.value = false
    created.value = res.data
    plaintextOpen.value = true
    // 与建项目同一约束:明文在屏期间不发后台请求,避免 401/424 被拦截器硬跳登录页时
    // 连同这枚仅此一次的明文一起销毁。列表刷新推迟到弹窗关闭之后。
  } finally {
    creating.value = false
  }
}

function closePlaintext() {
  plaintextOpen.value = false
  created.value = null // 明文只存活于弹窗生命周期
  loadKeys()
}

async function onRevoke(row: ApiKeyVO) {
  try {
    await ElMessageBox.confirm(
      `吊销后使用该 key 的调用方将立即失效(前缀 ${row.keyPrefix}…)。建议先创建新 key 并完成调用方切换。确认吊销?`,
      '吊销 API Key',
      { confirmButtonText: '吊销', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  await revokeKey(props.refId, row.id)
  ElMessage.success('已吊销')
  await loadKeys()
}

onMounted(loadKeys)
</script>

<style scoped>
.table-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px 8px;
}
.table-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--dc-ink);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tombstone {
  font-size: 12px;
  color: var(--dc-ink-subtle);
}
.key-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}
.key-value {
  flex: 1;
  font-size: 12px;
  word-break: break-all;
  background: var(--dc-fill-1);
  border-radius: 6px;
  padding: 8px 10px;
}
</style>
