<!-- @author 辰夕 -->
<template>
  <!-- 登录 -->
  <div v-if="mode === 'login'" class="auth">
    <div class="auth-card">
      <div class="auth-logo"><span class="dot">辰</span>辰夕</div>
      <div class="auth-sub">朝夕相伴的 AI 工作搭子</div>
      <label>邮箱</label>
      <input v-model.trim="loginForm.email" type="email" placeholder="you@company.com" />
      <label>密码</label>
      <input v-model="loginForm.password" type="password" placeholder="请输入密码"
             @keyup.enter="doLogin" />
      <div class="auth-err">{{ loginErr }}</div>
      <button class="auth-btn" :disabled="loading" @click="doLogin">登 录</button>
      <div class="auth-switch">还没有账号？<a @click="switchMode('register')">邮箱注册</a></div>
    </div>
  </div>

  <!-- 注册 -->
  <div v-else class="auth">
    <div class="auth-card">
      <div class="auth-logo"><span class="dot">辰</span>注册辰夕</div>
      <div class="auth-sub">使用邮箱创建你的账号</div>
      <label>昵称</label>
      <input v-model.trim="registerForm.nickname" placeholder="怎么称呼你" />
      <label>邮箱</label>
      <input v-model.trim="registerForm.email" type="email" placeholder="you@company.com" />
      <label>密码</label>
      <input v-model="registerForm.password" type="password" placeholder="至少 8 位，含字母和数字"
             @keyup.enter="doRegister" />
      <div class="auth-err">{{ registerErr }}</div>
      <button class="auth-btn" :disabled="loading" @click="doRegister">注 册</button>
      <div class="auth-switch">已有账号？<a @click="switchMode('login')">直接登录</a></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { toast } from '../utils/toast'

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const PASSWORD_MIN = 8

const router = useRouter()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const loginErr = ref('')
const registerErr = ref('')
const loginForm = reactive({ email: '', password: '' })
const registerForm = reactive({ nickname: '', email: '', password: '' })

function switchMode(next: 'login' | 'register') {
  mode.value = next
  loginErr.value = ''
  registerErr.value = ''
}

async function doLogin() {
  if (!EMAIL_RE.test(loginForm.email)) {
    loginErr.value = '请输入正确的邮箱'
    return
  }
  if (!loginForm.password) {
    loginErr.value = '请输入密码'
    return
  }
  loginErr.value = ''
  loading.value = true
  try {
    await auth.login(loginForm.email, loginForm.password)
    router.push('/chat')
  } catch (e: any) {
    loginErr.value = e.message ?? '登录失败'
  } finally {
    loading.value = false
  }
}

async function doRegister() {
  if (!registerForm.nickname) {
    registerErr.value = '请输入昵称'
    return
  }
  if (!EMAIL_RE.test(registerForm.email)) {
    registerErr.value = '请输入正确的邮箱'
    return
  }
  if (registerForm.password.length < PASSWORD_MIN) {
    registerErr.value = '密码至少 8 位'
    return
  }
  registerErr.value = ''
  loading.value = true
  try {
    await auth.register(registerForm.nickname, registerForm.email, registerForm.password)
    toast('注册成功，已自动登录')
    router.push('/chat')
  } catch (e: any) {
    registerErr.value = e.message ?? '注册失败'
  } finally {
    loading.value = false
  }
}
</script>
