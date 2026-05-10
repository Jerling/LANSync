<template>
  <div class="login-page">
    <div class="login-card">
      <h1>LANSync</h1>
      <p class="subtitle">{{ isRegister ? '创建新账户' : '相册云同步' }}</p>

      <form @submit.prevent="isRegister ? handleRegister() : handleLogin()">
        <div class="field">
          <label>服务器地址</label>
          <input
            v-model="serverUrl"
            type="url"
            placeholder="http://192.168.0.108:8765"
            required
            :disabled="loading"
          />
        </div>

        <div class="field">
          <label>用户名</label>
          <input
            v-model="username"
            type="text"
            placeholder="3位以上字母或数字"
            required
            :disabled="loading"
          />
        </div>

        <div class="field">
          <label>密码</label>
          <input
            v-model="password"
            type="password"
            placeholder="6位以上"
            required
            :disabled="loading"
          />
        </div>

        <div v-if="isRegister" class="field">
          <label>确认密码</label>
          <input
            v-model="confirmPassword"
            type="password"
            placeholder="再输入一次密码"
            required
            :disabled="loading"
          />
        </div>

        <p v-if="error" class="error">{{ error }}</p>

        <button type="submit" :disabled="loading">
          {{ loading ? (isRegister ? '注册中...' : '登录中...') : (isRegister ? '注册' : '登录') }}
        </button>

        <div class="switch-mode">
          <template v-if="!isRegister">
            没有账户？<button type="button" class="link-btn" @click="isRegister = true">立即注册</button>
          </template>
          <template v-else>
            已有账户？<button type="button" class="link-btn" @click="isRegister = false">去登录</button>
          </template>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'

const router = useRouter()
const serverUrl = ref(api.getBaseUrl() || 'http://192.168.0.108:8765')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)
const isRegister = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await api.login(username.value, password.value, serverUrl.value)
    router.push('/gallery')
  } catch (e: any) {
    error.value = e?.response?.data?.error || '登录失败，请检查服务器地址和账号密码'
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  error.value = ''
  if (password.value !== confirmPassword.value) {
    error.value = '两次密码不一致'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码至少6位'
    return
  }
  loading.value = true
  try {
    await api.register(username.value, password.value)
    // 注册成功后自动登录
    await api.login(username.value, password.value, serverUrl.value)
    router.push('/gallery')
  } catch (e: any) {
    error.value = e?.response?.data?.error || '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
}

.login-card {
  background: white;
  padding: 2.5rem 2rem;
  border-radius: 12px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.1);
  width: 100%;
  max-width: 360px;
}

h1 {
  margin: 0;
  font-size: 1.8rem;
  color: #1a1a2e;
  text-align: center;
}

.subtitle {
  margin: 0.25rem 0 2rem;
  color: #666;
  text-align: center;
  font-size: 0.9rem;
}

.field {
  margin-bottom: 1rem;
}

.field label {
  display: block;
  font-size: 0.85rem;
  font-weight: 500;
  margin-bottom: 0.35rem;
  color: #333;
}

.field input {
  width: 100%;
  padding: 0.6rem 0.75rem;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 1rem;
  box-sizing: border-box;
  transition: border-color 0.2s;
}

.field input:focus {
  outline: none;
  border-color: #4f8aff;
}

.field input:disabled {
  background: #f5f5f5;
}

.error {
  color: #e53e3e;
  font-size: 0.85rem;
  margin: 0 0 1rem;
}

button {
  width: 100%;
  padding: 0.7rem;
  background: #4f8aff;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 1rem;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}

button:hover:not(:disabled) {
  background: #3a7aef;
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.switch-mode {
  margin-top: 1.25rem;
  text-align: center;
  font-size: 0.85rem;
  color: #666;
}

.link-btn {
  width: auto;
  background: none;
  color: #4f8aff;
  padding: 0;
  font-size: 0.85rem;
  display: inline;
  text-decoration: underline;
}

.link-btn:hover {
  background: none;
  color: #3a7aef;
}
</style>
