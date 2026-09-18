<!-- @author 辰夕 -->
<template>
  <div class="main">
    <div class="mem">
      <h2>🧠 记忆中心</h2>
      <div class="tabs">
        <div :class="['tab', { active: tab === 'user' }]" @click="tab = 'user'">用户记忆</div>
        <div :class="['tab', { active: tab === 'session' }]" @click="tab = 'session'">会话记忆</div>
      </div>

      <!-- 用户记忆：跨会话长期记忆（MEMORY.md 条目镜像） -->
      <template v-if="tab === 'user'">
        <div v-if="memories.length === 0" class="market-empty">
          暂无长期记忆。对话中透露的稳定偏好与事实，小梓会自动记住并出现在这里。
        </div>
        <div v-for="m in memories" :key="m.id" class="mem-card">
          <div class="mi">🧠</div>
          <div class="mc">
            <template v-if="editingId === m.id">
              <textarea v-model="editingContent" class="mem-edit" rows="3" maxlength="1000"></textarea>
              <div class="mem-edit-ops">
                <button class="btn btn-primary mem-btn" :disabled="saving" @click="onSaveEdit(m)">
                  {{ saving ? '保存中…' : '保存' }}
                </button>
                <button class="btn btn-ghost mem-btn" :disabled="saving" @click="editingId = 0">取消</button>
              </div>
            </template>
            <template v-else>
              <b>{{ m.content }}</b>
              <div>
                {{ m.sourceSessionTitle ? `来源会话「${m.sourceSessionTitle}」 · ` : '' }}更新于
                {{ formatTime(m.updatedAt) }}
              </div>
            </template>
          </div>
          <template v-if="editingId !== m.id">
            <button class="mem-op" @click="startEdit(m)">编辑</button>
            <button class="mem-op danger" @click="onRemove(m)">删除</button>
          </template>
        </div>
        <button v-if="memories.length > 0" class="clear-all" @click="onClearAll">🗑 一键清空全部记忆</button>
      </template>

      <!-- 会话记忆：各会话的聊天历史，点击跳回对话页 -->
      <template v-else>
        <div v-if="sessions.length === 0" class="market-empty">暂无会话</div>
        <div v-for="s in sessions" :key="s.sessionId" class="mem-card mem-session" @click="openSession(s)">
          <div class="mi">💬</div>
          <div class="mc">
            <b>{{ s.title }}</b>
            <div>更新于 {{ formatTime(s.updatedAt) }}</div>
          </div>
          <span class="mem-go">继续对话 →</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { memoryApi, sessionApi, type MemoryItem, type SessionItem } from '../api'
import { toast } from '../utils/toast'
import { confirmDialog } from '../utils/confirm'

const router = useRouter()

const tab = ref<'user' | 'session'>('user')
const memories = ref<MemoryItem[]>([])
const sessions = ref<SessionItem[]>([])
const editingId = ref(0)
const editingContent = ref('')
const saving = ref(false)

onMounted(async () => {
  await Promise.all([loadMemories(), loadSessions()])
})

async function loadMemories() {
  try {
    memories.value = await memoryApi.list()
  } catch (e: any) {
    toast(e.message ?? '记忆列表加载失败')
  }
}

async function loadSessions() {
  try {
    sessions.value = await sessionApi.list()
  } catch (e: any) {
    toast(e.message ?? '会话列表加载失败')
  }
}

function startEdit(m: MemoryItem) {
  editingId.value = m.id
  editingContent.value = m.content
}

async function onSaveEdit(m: MemoryItem) {
  const content = editingContent.value.trim()
  if (!content) {
    toast('记忆内容不能为空')
    return
  }
  saving.value = true
  try {
    await memoryApi.update(m.id, content)
    toast('记忆已更新')
    editingId.value = 0
    await loadMemories()
  } catch (e: any) {
    toast(e.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function onRemove(m: MemoryItem) {
  if (!(await confirmDialog(`确定删除这条记忆？\n「${m.content.slice(0, 50)}」`, { title: '删除记忆', confirmText: '删除' }))) return
  try {
    await memoryApi.remove(m.id)
    toast('记忆已删除')
    await loadMemories()
  } catch (e: any) {
    toast(e.message ?? '删除失败')
  }
}

async function onClearAll() {
  if (!(await confirmDialog('确定清空全部长期记忆？将同时删除记忆文件，不可恢复。', { title: '清空记忆', confirmText: '全部清空' }))) return
  try {
    await memoryApi.clearAll()
    toast('已清空全部记忆')
    await loadMemories()
  } catch (e: any) {
    toast(e.message ?? '清空失败')
  }
}

function openSession(s: SessionItem) {
  router.push({ path: '/chat', query: { session: s.sessionId } })
}

function formatTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}
</script>
