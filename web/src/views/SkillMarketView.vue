<!-- @author 辰夕 -->
<template>
  <div class="main">
    <div class="market">
      <div class="market-head">
        <h2>🧩 技能市场</h2>
        <input v-model="keyword" class="search" placeholder="搜索技能…" @input="load" />
        <button v-if="canImport" class="upload-btn" @click="zipInputRef?.click()">＋ 导入技能</button>
        <input ref="zipInputRef" type="file" accept=".zip" style="display: none" @change="onImport" />
      </div>
      <div class="tabs" style="margin-bottom: 18px">
        <div :class="['tab', { active: tab === 'PUBLIC' }]" @click="switchTab('PUBLIC')">🌐 公共技能</div>
        <div :class="['tab', { active: tab === 'USER' }]" @click="switchTab('USER')">🔒 我的技能</div>
      </div>
      <div v-if="skills.length === 0" class="market-empty">
        暂无技能{{ canImport ? '，点击右上角「导入技能」上传 zip 包' : '' }}
      </div>
      <div class="skill-grid">
        <div v-for="s in skills" :key="s.id" class="skill-card" @click="router.push(`/skills/${s.id}`)">
          <div class="sic" :style="{ background: skillBg(s.skillKey) }">{{ skillIcon(s.skillKey) }}</div>
          <h3>
            {{ s.skillKey }}
            <span :class="['scope-badge', s.scope === 'PUBLIC' ? 'pub' : 'pri']">
              {{ s.scope === 'PUBLIC' ? '公共' : '私人' }}
            </span>
          </h3>
          <p>{{ s.description }}</p>
          <div class="skill-meta"><span v-for="t in s.tags" :key="t" class="tag">{{ t }}</span></div>
          <div class="ver">更新于 {{ formatTime(s.updatedAt) }} · {{ s.fileCount }} 个文件</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { skillApi, type SkillSummary } from '../api'
import { useAuthStore } from '../stores/auth'
import { skillBg, skillIcon } from '../utils/skillMeta'
import { toast } from '../utils/toast'

const router = useRouter()
const auth = useAuthStore()

const tab = ref<'PUBLIC' | 'USER'>('PUBLIC')
const keyword = ref('')
const skills = ref<SkillSummary[]>([])
const zipInputRef = ref<HTMLInputElement>()

/** 导入按钮：我的技能人人可导；公共区仅管理员 */
const canImport = computed(() => tab.value === 'USER' || auth.profile?.role === 'ADMIN')

onMounted(async () => {
  if (!auth.profile) await auth.fetchProfile()
  await load()
})

function switchTab(t: 'PUBLIC' | 'USER') {
  tab.value = t
  load()
}

async function load() {
  try {
    skills.value = await skillApi.list(tab.value, keyword.value.trim() || undefined)
  } catch (e: any) {
    toast(e.message ?? '技能列表加载失败')
  }
}

async function onImport(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    await skillApi.importZip(file, tab.value)
    toast('技能导入成功')
    await load()
  } catch (err: any) {
    toast(err.message ?? '导入失败')
  }
}

function formatTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}
</script>
