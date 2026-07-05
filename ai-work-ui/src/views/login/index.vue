<template>
  <div class="flex h-screen items-center justify-center bg-gray-50">
    <el-card class="w-96">
      <div class="mb-6 text-center text-xl font-bold">AI Work Platform</div>
      <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item prop="code">
          <div class="flex w-full items-center gap-2">
            <el-input v-model="form.code" placeholder="验证码" class="flex-1" />
            <img
              :src="codeUrl"
              alt="验证码，点击刷新"
              class="h-8 w-24 cursor-pointer rounded border border-gray-200"
              @click="refreshCode"
            />
          </div>
        </el-form-item>
        <el-button type="primary" class="w-full" :loading="loading" @click="onSubmit">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { imageCodeUrl } from '@/api/login'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const codeUrl = ref('')
const form = reactive({ username: '', password: '', code: '', randomStr: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}

function refreshCode() {
  form.randomStr = `${Date.now()}${Math.floor(Math.random() * 1000)}`
  codeUrl.value = imageCodeUrl(form.randomStr)
  form.code = ''
}
refreshCode()

async function onSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(form)
    router.push((route.query.redirect as string) || '/')
  } catch (e) {
    // token 端点失败时优先展示后端消息（R.msg 或 OAuth2 error_description）
    const data = (e as { response?: { data?: { msg?: string; error_description?: string } } })
      .response?.data
    ElMessage.error(data?.msg || data?.error_description || '登录失败')
    refreshCode()
  } finally {
    loading.value = false
  }
}
</script>
