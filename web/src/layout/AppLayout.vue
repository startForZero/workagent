<!-- @author 辰夕 -->
<template>
  <div class="app">
    <aside class="sidebar">
      <div class="logo"><span class="dot">辰</span>辰夕</div>
      <div class="new-chat" @click="onNewChat">＋ 新对话</div>

      <div class="conv-list">
        <template v-for="group in sessionGroups" :key="group.label">
          <div v-if="group.items.length" class="side-label">{{ group.label }}</div>
          <div v-for="s in group.items" :key="s.sessionId"
               :class="['conv', { active: s.sessionId === chat.currentSessionId }]"
               @click="onSwitch(s.sessionId)">
            {{ s.title }}
          </div>
        </template>
      </div>

      <div class="nav">
        <div class="nav-item" @click="toast('技能市场将在 M3 上线')">🧩 技能市场</div>
        <div class="nav-item" @click="toast('记忆中心将在 M4 上线')">🧠 记忆中心</div>
        <div :class="['nav-item', { active: route.name === 'settings' }]" @click="router.push('/settings')">
          ⚙️ 设置
        </div>
        <div class="user-chip" @click="router.push('/settings')">
          <div class="uav">{{ avatarLetter }}</div>
          <div>
            <div class="un">{{ auth.profile?.nickname ?? '…' }}</div>
            <div class="ue">{{ auth.profile?.email ?? '' }}</div>
          </div>
        </div>
      </div>
    </aside>

    <router-view />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { SessionItem } from '../api'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'
import { toast } from '../utils/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()

const DAY_MS = 24 * 60 * 60 * 1000

const avatarLetter = computed(() => auth.profile?.nickname?.charAt(0) || '我')

/** 按更新时间分组：今天 / 昨天 / 更早（与原型侧栏分组一致） */
const sessionGroups = computed(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const groups: { label: string; items: SessionItem[] }[] = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '更早', items: [] }
  ]
  for (const s of chat.sessions) {
    const t = new Date(s.updatedAt).getTime()
    if (t >= todayStart) groups[0].items.push(s)
    else if (t >= todayStart - DAY_MS) groups[1].items.push(s)
    else groups[2].items.push(s)
  }
  return groups
})

onMounted(async () => {
  if (!auth.profile) await auth.fetchProfile()
  await chat.init()
})

async function onNewChat() {
  await chat.newSession()
  router.push('/chat')
}

function onSwitch(sessionId: string) {
  chat.select(sessionId)
  router.push('/chat')
}
</script>
