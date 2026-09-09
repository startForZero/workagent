<!-- @author 辰夕 -->
<template>
  <div class="main">
    <div class="settings">
      <h2>⚙️ 设置</h2>
      <div class="tabs">
        <div :class="['tab', { active: tab === 'profile' }]" @click="tab = 'profile'">👤 个人资料</div>
        <div :class="['tab', { active: tab === 'models' }]" @click="tab = 'models'">🤖 模型管理</div>
      </div>

      <!-- 个人资料 -->
      <div v-if="tab === 'profile'" class="set-panel">
        <div class="set-card">
          <h3>基本信息</h3>
          <div class="desc">这些信息会展示在你的个人中心</div>
          <div class="avatar-row">
            <div class="avatar-lg">{{ avatarLetter }}</div>
            <button class="btn btn-ghost" @click="toast('头像上传将在后续版本支持')">更换头像</button>
          </div>
          <div class="form-row">
            <label>昵称</label>
            <input v-model.trim="profileForm.nickname" />
          </div>
          <div class="form-row">
            <label>邮箱（登录账号，不可修改）</label>
            <input :value="auth.profile?.email" disabled />
          </div>
          <div class="form-row">
            <label>个人简介</label>
            <input v-model.trim="profileForm.bio" placeholder="一句话介绍自己（选填）" />
          </div>
          <button class="btn btn-primary" :disabled="savingProfile" @click="saveProfile">保存修改</button>
        </div>

        <div class="set-card">
          <h3>修改密码</h3>
          <div class="desc">修改后需重新登录</div>
          <div class="form-row">
            <label>当前密码</label>
            <input v-model="pwdForm.current" type="password" placeholder="请输入当前密码" />
          </div>
          <div class="form-row">
            <label>新密码</label>
            <input v-model="pwdForm.next" type="password" placeholder="至少 8 位，含字母和数字" />
          </div>
          <div class="form-row">
            <label>确认新密码</label>
            <input v-model="pwdForm.confirm" type="password" placeholder="再次输入新密码" />
          </div>
          <button class="btn btn-primary" :disabled="savingPwd" @click="changePwd">确认修改</button>
        </div>

        <div class="set-card">
          <h3>账号</h3>
          <div class="desc">退出当前登录状态</div>
          <button class="btn btn-ghost" @click="logout">退出登录</button>
        </div>
      </div>

      <!-- 模型管理 -->
      <div v-else class="set-panel">
        <div class="set-card">
          <h3>我的模型</h3>
          <div class="desc">添加后可随时在对话输入区切换；API Key 加密存储，仅服务端可见</div>
          <div v-if="models.length === 0" style="font-size: 13px; color: var(--sub); padding: 10px 0 16px">
            还没有模型，点击下方按钮添加一个开始对话吧
          </div>
          <div v-for="m in models" :key="m.id" class="model-card">
            <span class="pv" :style="{ background: providerMeta(m.provider).color }">
              {{ providerMeta(m.provider).letter }}
            </span>
            <div class="mi">
              <b>{{ providerMeta(m.provider).name }} · {{ m.model }}</b>
              <div>{{ m.baseUrl || '默认接入点' }}</div>
            </div>
            <span :class="['st', { off: !m.enabled }]">{{ m.enabled ? '已启用' : '已停用' }}</span>
            <button @click="toggle(m)">{{ m.enabled ? '停用' : '启用' }}</button>
            <button @click="removeModel(m)">删除</button>
          </div>
          <button class="add-model-btn" @click="showAddForm">＋ 添加模型</button>
        </div>

        <!-- 添加模型表单（默认隐藏） -->
        <div v-if="addVisible" class="set-card">
          <h3>添加模型</h3>
          <div class="desc">选择服务商，填入 API Key 即可；支持所有 OpenAI 兼容接口</div>
          <div class="provider-grid">
            <div v-for="p in PROVIDERS" :key="p.id"
                 :class="['pv-item', { selected: p.id === addForm.provider }]"
                 @click="selectProvider(p.id)">
              <div class="pv" :style="{ background: p.color }">{{ p.letter }}</div>
              {{ p.name }}
            </div>
          </div>
          <div class="form-row">
            <label>API Key <span class="req">*</span></label>
            <input v-model.trim="addForm.apiKey" type="password" placeholder="sk-..." />
            <div class="hint">密钥加密存储，不会出现在日志与对话上下文中</div>
          </div>
          <div class="form-row">
            <label>模型名称 <span class="req">*</span></label>
            <input v-model.trim="addForm.model" placeholder="如 deepseek-chat" />
          </div>
          <div class="form-row">
            <label>接口地址（Base URL）</label>
            <input v-model.trim="addForm.baseUrl" placeholder="选择服务商后自动填充，可修改" />
          </div>
          <div style="display: flex; gap: 10px">
            <button class="btn btn-primary" :disabled="savingModel" @click="saveModel">
              {{ savingModel ? '测试并保存中…' : '测试并保存' }}
            </button>
            <button class="btn btn-ghost" @click="addVisible = false">取消</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { modelApi, userApi, type UserModel } from '../api'
import { PROVIDERS, providerMeta } from '../constants/providers'
import { useAuthStore } from '../stores/auth'
import { toast } from '../utils/toast'

const route = useRoute()
const auth = useAuthStore()

const tab = ref((route.query.tab as string) || 'profile')

const avatarLetter = computed(() => auth.profile?.nickname?.charAt(0) || '我')

// ---------- 个人资料 ----------
const profileForm = reactive({ nickname: '', bio: '' })
const pwdForm = reactive({ current: '', next: '', confirm: '' })
const savingProfile = ref(false)
const savingPwd = ref(false)

onMounted(async () => {
  if (!auth.profile) await auth.fetchProfile()
  profileForm.nickname = auth.profile?.nickname ?? ''
  profileForm.bio = auth.profile?.bio ?? ''
  await loadModels()
  if (route.query.action === 'add') showAddForm()
})

async function saveProfile() {
  if (!profileForm.nickname) {
    toast('请输入昵称')
    return
  }
  savingProfile.value = true
  try {
    auth.profile = await userApi.update({ ...profileForm })
    toast('个人资料已保存')
  } catch (e: any) {
    toast(e.message ?? '保存失败')
  } finally {
    savingProfile.value = false
  }
}

async function changePwd() {
  if (!pwdForm.current || !pwdForm.next) {
    toast('请填写完整密码信息')
    return
  }
  if (pwdForm.next !== pwdForm.confirm) {
    toast('两次输入的新密码不一致')
    return
  }
  savingPwd.value = true
  try {
    await userApi.changePassword(pwdForm.current, pwdForm.next)
    toast('密码已修改，请重新登录')
    auth.logout()
  } catch (e: any) {
    toast(e.message ?? '修改失败')
  } finally {
    savingPwd.value = false
  }
}

function logout() {
  if (window.confirm('确定退出登录吗？')) auth.logout()
}

// ---------- 模型管理 ----------
const models = ref<UserModel[]>([])
const addVisible = ref(false)
const savingModel = ref(false)
const addForm = reactive({ provider: 'deepseek', model: '', baseUrl: '', apiKey: '' })

async function loadModels() {
  models.value = await modelApi.list()
}

function showAddForm() {
  addVisible.value = true
  addForm.apiKey = ''
  selectProvider('deepseek')
}

function selectProvider(id: string) {
  addForm.provider = id
  const meta = providerMeta(id)
  addForm.baseUrl = meta.baseUrl
  addForm.model = meta.model
}

async function saveModel() {
  if (!addForm.apiKey) {
    toast('请填写 API Key')
    return
  }
  if (!addForm.model) {
    toast('请填写模型名称')
    return
  }
  savingModel.value = true
  try {
    await modelApi.create({ ...addForm })
    addVisible.value = false
    await loadModels()
    toast('连通性测试通过，模型已添加，可在输入区切换使用')
  } catch (e: any) {
    toast(e.message ?? '添加失败')
  } finally {
    savingModel.value = false
  }
}

async function toggle(m: UserModel) {
  try {
    await modelApi.setEnabled(m.id, !m.enabled)
    await loadModels()
    toast(m.enabled ? '已停用该模型' : '已启用该模型')
  } catch (e: any) {
    toast(e.message ?? '操作失败')
  }
}

async function removeModel(m: UserModel) {
  if (!window.confirm(`确认删除模型「${providerMeta(m.provider).name} · ${m.model}」？使用该模型的会话将不可用。`)) return
  try {
    await modelApi.remove(m.id)
    await loadModels()
    toast('已删除模型')
  } catch (e: any) {
    toast(e.message ?? '删除失败')
  }
}
</script>
