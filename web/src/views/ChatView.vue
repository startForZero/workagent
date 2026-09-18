<!-- @author 辰夕 -->
<template>
  <div class="chat-layout">
    <div class="main">
      <div class="topbar">
        <span class="topbar-title">{{ currentTitle }}</span>
        <span v-if="running" class="status-pill">⏳ 小梓生成中…</span>
        <button class="panel-toggle" @click="panelOpen = !panelOpen">
          📦 产物<span v-if="artifacts.length" class="panel-badge">{{ artifacts.length }}</span>
        </button>
      </div>

      <div ref="chatScrollRef" class="chat">
        <div class="chat-inner">
          <div v-if="messages.length === 0" class="chat-empty">
            和小梓开始一段对话吧<br />
            <span style="font-size: 12px">支持上传文件、随时停止生成；输入区可切换模型</span>
          </div>

          <div v-for="(m, i) in messages" :key="i" class="msg">
            <div :class="['avatar', m.role]">{{ m.role === 'user' ? '👤' : '梓' }}</div>
            <div class="msg-body">
              <!-- 用户消息：技能/附件 chips + 气泡 -->
              <template v-if="m.role === 'user'">
                <div class="bubble-user">
                  <div v-for="(k, ki) in m.skills" :key="'sk' + ki" class="chip" style="margin: 0 6px 8px 0">
                    🧩 <b>{{ k }}</b>
                  </div>
                  <div v-for="(f, fi) in m.files" :key="fi" class="chip" style="margin: 0 6px 8px 0">
                    📎 <b>{{ f.name }}</b><span>{{ f.size }}</span>
                  </div>
                  {{ m.text }}
                </div>
              </template>

              <!-- 助手消息：过程容器（思考/工具步骤流）+ 最终答案 -->
              <template v-else>
                <div v-if="m.steps.length" :class="['proc', { open: m.processOpen }]">
                  <div class="proc-head" @click="m.processOpen = !m.processOpen">
                    <span>🧠</span>
                    <span>{{ m.streaming ? '小梓思考与执行中…' : '思考与执行过程' }}</span>
                    <span class="proc-count">{{ m.steps.length }} 步</span>
                    <span class="proc-arrow">▸</span>
                  </div>
                  <div class="proc-body">
                    <template v-for="(s, si) in m.steps" :key="si">
                      <!-- 思考步骤：一行可展开（WorkBuddy 风格） -->
                      <div v-if="s.kind === 'think'" :class="['prow', { open: s.open }]">
                        <div class="prow-head" @click="s.open = !s.open">
                          <span class="prow-ico">🧠</span>
                          <span>{{ m.streaming && si === m.steps.length - 1 ? '正在深度思考…' : '已深度思考' }}</span>
                          <span class="prow-arrow">▸</span>
                        </div>
                        <div class="prow-body prow-think">{{ s.text }}</div>
                      </div>
                      <!-- 阶段结论（工具之间的中间文本块）：Markdown + HTML 预览 -->
                      <RichText v-else-if="s.kind === 'text'" class="prow-text" :text="s.text" />
                      <!-- HITL 风险确认卡：允许/拒绝 -->
                      <div v-else-if="s.kind === 'confirm'" class="cf-card">
                        <div class="cf-head">⚠️ 小梓请求执行风险操作</div>
                        <div v-for="t in s.tools" :key="t.id" class="cf-tool">
                          <div class="cf-tool-name">{{ toolLabel(t.name) }}</div>
                          <pre class="prow-pre">{{ prettyJson(t.args) }}</pre>
                        </div>
                        <div v-if="s.status === 'pending'" class="cf-actions">
                          <button class="cf-btn allow" :disabled="resuming" @click="confirmResolve(m, s, true)">
                            ✓ 允许
                          </button>
                          <button class="cf-btn deny" :disabled="resuming" @click="confirmResolve(m, s, false)">
                            ✕ 拒绝
                          </button>
                        </div>
                        <div v-else :class="['cf-result', s.status]">
                          {{ s.status === 'approved' ? '✓ 已允许并继续执行' : '✕ 已拒绝' }}
                        </div>
                      </div>
                      <!-- 参数补全表单卡：ask_user 挂起，用户填写提交后续跑 -->
                      <div v-else-if="s.kind === 'param'" class="pf-card">
                        <div class="pf-head">📝 小梓需要你补充信息</div>
                        <div v-if="s.question" class="pf-question">{{ s.question }}</div>
                        <div v-for="f in s.fields" :key="f.key" class="pf-field">
                          <label class="pf-label">
                            {{ f.label || f.key }}<i v-if="f.required" class="pf-req">*</i>
                          </label>
                          <textarea v-if="f.type === 'textarea'" v-model="s.values[f.key]" rows="3"
                                    class="pf-input" :placeholder="f.placeholder ?? ''"
                                    :disabled="s.status !== 'pending' || resuming" />
                          <select v-else-if="f.type === 'select'" v-model="s.values[f.key]"
                                  class="pf-input" :disabled="s.status !== 'pending' || resuming">
                            <option value="" disabled>{{ f.placeholder ?? '请选择' }}</option>
                            <option v-for="opt in f.options ?? []" :key="opt" :value="opt">{{ opt }}</option>
                          </select>
                          <input v-else v-model="s.values[f.key]" class="pf-input"
                                 :type="f.type === 'number' ? 'number' : 'text'"
                                 :placeholder="f.placeholder ?? ''"
                                 :disabled="s.status !== 'pending' || resuming" />
                        </div>
                        <div v-if="s.status === 'pending'" class="pf-actions">
                          <button class="pf-submit" :disabled="resuming" @click="submitAnswer(m, s)">
                            {{ resuming ? '提交中…' : '提交并继续' }}
                          </button>
                        </div>
                        <div v-else class="pf-result">✓ 已提交，继续执行</div>
                      </div>
                      <!-- 工具步骤：状态图标 + 友好名 + 参数摘要，一行可展开 -->
                      <div v-else :class="['prow', { open: s.open }]">
                        <div class="prow-head" @click="s.open = !s.open">
                          <span :class="['prow-ck', s.status]">{{ s.status === 'running' ? '⏳' : '✓' }}</span>
                          <span class="prow-tool-name">{{ toolLabel(s.name) }}:</span>
                          <span class="prow-tool-brief">{{ toolBrief(s) }}</span>
                          <span class="prow-arrow">▸</span>
                        </div>
                        <div class="prow-body">
                          <template v-if="s.args">
                            <div class="prow-kv-label">参数</div>
                            <pre class="prow-pre">{{ s.args }}</pre>
                          </template>
                          <template v-if="s.result">
                            <div class="prow-kv-label">结果</div>
                            <pre class="prow-pre">{{ s.result }}</pre>
                          </template>
                        </div>
                      </div>
                    </template>
                  </div>
                </div>

                <RichText v-if="m.text" class="msg-text" :class="{ cursor: m.streaming }" :text="m.text" />
                <span v-else-if="m.streaming" class="cursor" />
                <div v-if="m.error" class="msg-error">⚠ {{ m.error }}</div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区：附件 + 模型选择 -->
      <div class="inputbar">
        <div class="input-inner">
          <div class="attach-chips">
            <div v-for="(s, i) in selectedSkills" :key="s.skillKey" class="chip">
              🧩 <b>{{ s.skillKey }}</b>
              <i @click="selectedSkills.splice(i, 1)">✕</i>
            </div>
            <div v-for="(f, i) in attachments" :key="f.fileId" class="chip">
              📎 <b>{{ f.filename }}</b><span>{{ formatSize(f.size) }}</span>
              <i @click="attachments.splice(i, 1)">✕</i>
            </div>
          </div>
          <div class="input-box">
            <textarea ref="textareaRef" v-model="input" rows="2"
                      :placeholder="pendingAction ? '请先处理上方的确认/表单' : '输入消息，@ 唤起技能…'"
                      :disabled="!!pendingAction"
                      @input="onInput" @keydown.enter.exact.prevent="send"
                      @keydown.esc="closeModelMenu" />
            <div class="input-toolbar">
              <button class="tool-btn" title="上传文件" @click="fileInputRef?.click()">📎</button>
              <input ref="fileInputRef" type="file" multiple style="display: none" @change="onFiles" />
              <div class="model-picker">
                <button class="tool-btn" title="@ 技能" @click.stop="toggleSkillMenu">@</button>
                <div v-if="skillMenuOpen" class="skill-menu" @click.stop>
                  <div v-if="menuSkills.length === 0" class="skill-empty">
                    {{ availableSkills.length === 0 ? '还没有可用技能，去「技能市场」导入一个吧' : '没有匹配的技能' }}
                  </div>
                  <div v-for="s in menuSkills" :key="s.skillKey" class="skill-item"
                       @click="toggleSkill(s)">
                    <span class="sic" :style="{ background: skillBg(s.skillKey) }">
                      {{ skillIcon(s.skillKey) }}
                    </span>
                    <span class="sn">{{ s.skillKey }}<small>{{ s.description }}</small></span>
                    <span v-if="isSkillSelected(s)" class="ck">✓</span>
                  </div>
                </div>
              </div>
              <div class="model-picker">
                <button class="model-btn" @click.stop="modelMenuOpen = !modelMenuOpen">
                  <span>{{ currentModelLabel }}</span> ▾
                </button>
                <div v-if="modelMenuOpen" class="model-menu" @click.stop>
                  <div v-if="enabledModels.length === 0" class="model-empty">
                    还没有可用模型，先添加一个吧
                  </div>
                  <div v-for="m in enabledModels" :key="m.modelKey"
                       :class="['model-item', { selected: m.modelKey === modelKey }]"
                       @click="pickModel(m.modelKey)">
                    <span class="pv" :style="{ background: providerMeta(m.provider).color }">
                      {{ providerMeta(m.provider).letter }}
                    </span>
                    <span class="mn">{{ providerMeta(m.provider).name }}<small>{{ m.model }}</small></span>
                    <span v-if="m.modelKey === modelKey" class="ck">✓</span>
                  </div>
                  <div class="model-add" @click="goAddModel">＋ 添加模型</div>
                </div>
              </div>
              <button v-if="!running" class="send" :disabled="!canSend" title="发送" @click="send">➤</button>
              <button v-else class="send stop" title="停止生成" @click="stop">⏹</button>
            </div>
          </div>
          <div class="input-tips">
            Enter 发送 · Shift+Enter 换行 · 📎 支持上传文件（pdf/docx/xlsx/csv/图片）· ⏹ 可随时停止生成
          </div>
        </div>
      </div>
    </div>

    <!-- 产物面板：deliver_artifact 归档的文件，点击下载 -->
    <aside v-if="panelOpen" class="artifact-panel">
      <div class="ap-head">
        <span>📦 产物</span>
        <button class="ap-close" @click="panelOpen = false">✕</button>
      </div>
      <div v-if="artifacts.length === 0" class="ap-empty">
        小梓产出的文件（图表/报表/代码等）会归档在这里
      </div>
      <div v-for="a in artifacts" :key="a.id" class="ap-item" @click="downloadArtifact(a)">
        <div class="ap-icon">📄</div>
        <div class="ap-meta">
          <div class="ap-name" :title="a.fileName">{{ a.fileName }}</div>
          <div class="ap-sub">{{ formatSize(a.size) }} · {{ formatTime(a.createdAt) }}</div>
        </div>
        <div class="ap-dl">⬇</div>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { artifactApi, fileApi, modelApi, sessionApi, skillApi, type ArtifactItem, type SkillSummary, type UploadedFile, type UserModel } from '../api'
import { streamAnswer, streamResume, streamRun, type SseEnvelope } from '../api/sse'
import { getToken } from '../router'
import { providerMeta } from '../constants/providers'
import { toolLabel } from '../constants/tools'
import { useChatStore } from '../stores/chat'
import RichText from '../components/RichText.vue'
import { skillBg, skillIcon } from '../utils/skillMeta'
import { toast } from '../utils/toast'

/** SSE 事件名契约（与后端 SseEventType 对应） */
const EVT = {
  RUN_START: 'run.start',
  THINKING_DELTA: 'thinking.delta',
  TEXT_DELTA: 'text.delta',
  TOOL_CALL: 'tool.call',
  TOOL_RESULT: 'tool.result',
  HITL_CONFIRM: 'hitl.confirm',
  HITL_CONFIRM_RESOLVED: 'hitl.confirm_resolved',
  HITL_ASK_PARAM: 'hitl.ask_param',
  HITL_PARAM_RESOLVED: 'hitl.param_resolved',
  ARTIFACT_CREATED: 'artifact.created',
  RUN_END: 'run.end',
  RUN_ERROR: 'run.error'
} as const

const PAYLOAD_MAX = 600
/** 工具行参数摘要最大长度 */
const BRIEF_MAX = 48

/** 框架原始事件子类型（envelope.data.type，用于区分同一 SSE 事件下的开始/增量/结束） */
const RAW = {
  THINKING_START: 'THINKING_BLOCK_START',
  TEXT_START: 'TEXT_BLOCK_START',
  TOOL_CALL_START: 'TOOL_CALL_START',
  TOOL_CALL_DELTA: 'TOOL_CALL_DELTA',
  TOOL_CALL_END: 'TOOL_CALL_END',
  TOOL_RESULT_END: 'TOOL_RESULT_END'
} as const

/** HITL 待确认工具调用（hitl.confirm 事件 toolCalls 负载） */
interface ConfirmTool {
  id: string
  name: string
  args: string
}

/** 参数补全表单的字段定义（ask_user 工具 fields 入参，模型生成） */
interface ParamField {
  key: string
  label: string
  type: 'text' | 'textarea' | 'number' | 'select'
  required?: boolean
  placeholder?: string
  options?: string[]
}

/**
 * 过程步骤（WorkBuddy 风格行内步骤行）：
 * think=思考，text=阶段结论（工具之间的中间文本块），tool=工具调用，
 * confirm=HITL 风险确认，param=参数补全表单
 */
interface Step {
  kind: 'think' | 'text' | 'tool' | 'confirm' | 'param'
  text: string
  toolCallId: string
  name: string
  args: string
  result: string
  status: 'running' | 'done' | 'pending' | 'approved' | 'rejected' | 'resolved'
  open: boolean
  replyId: string
  tools: ConfirmTool[]
  /** param 步骤：提问、表单字段、用户填写值（pending 时为编辑中的草稿） */
  question: string
  fields: ParamField[]
  values: Record<string, any>
}

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  files?: { name: string; size: string }[]
  /** 用户消息上 @ 唤起的技能（skillKey），历史回放还原 chip */
  skills?: string[]
  steps: Step[]
  processOpen: boolean
  error?: string
  streaming?: boolean
  /** 所在 run（HITL 挂起消息 resume 用；历史回放时由后端带出） */
  runId?: string
}

const route = useRoute()
const router = useRouter()
const chat = useChatStore()

const messages = reactive<ChatMessage[]>([])
const input = ref('')
const running = ref(false)
/** resume 流进行中（确认卡按钮防重复点击） */
const resuming = ref(false)
const attachments = ref<UploadedFile[]>([])
const models = ref<UserModel[]>([])
const modelKey = ref<string>()
const modelMenuOpen = ref(false)
/** @ 技能选择器 */
const skillMenuOpen = ref(false)
const availableSkills = ref<SkillSummary[]>([])
const selectedSkills = ref<SkillSummary[]>([])
/** 打字唤起态：@ 字符在文本中的下标；-1 表示非打字唤起（工具栏按钮唤起） */
const atStart = ref(-1)
/** @ 后面已输入的关键词（用于过滤） */
const atKeyword = ref('')
const chatScrollRef = ref<HTMLElement>()
const textareaRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const currentRunId = ref('')
const artifacts = ref<ArtifactItem[]>([])
const panelOpen = ref(false)
let abortController: AbortController | null = null

const enabledModels = computed(() => models.value.filter((m) => m.enabled))
const currentModelLabel = computed(() => {
  const m = models.value.find((x) => x.modelKey === modelKey.value)
  return m ? `${providerMeta(m.provider).name} · ${m.model}` : '选择模型'
})
const currentTitle = computed(
  () => chat.sessions.find((s) => s.sessionId === chat.currentSessionId)?.title ?? '新对话'
)
/** 是否存在待处理的人机交互卡（风险确认或参数表单；存在则禁用输入） */
const pendingAction = computed(() => {
  const last = messages[messages.length - 1]
  return last?.steps.some(
    (s) => (s.kind === 'confirm' || s.kind === 'param') && s.status === 'pending'
  ) ?? false
})
const canSend = computed(
  () => input.value.trim().length > 0 && !!chat.currentSessionId && !pendingAction.value
)

onMounted(async () => {
  document.addEventListener('click', closeModelMenu)
  await loadModels()
})

/** 从记忆中心等入口带 ?session= 跳转：会话列表（AppLayout 异步加载）就绪后选中目标会话 */
watch(
  () => [route.query.session, chat.sessions.length] as const,
  ([target]) => {
    if (typeof target === 'string' && target && chat.sessions.some((s) => s.sessionId === target)) {
      chat.select(target)
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  document.removeEventListener('click', closeModelMenu)
  abortController?.abort()
})

watch(
  () => chat.currentSessionId,
  (sessionId) => {
    if (running.value) stop()
    loadHistory(sessionId)
    loadArtifacts(sessionId)
  },
  { immediate: true }
)

/** 历史消息落库结构（与后端 RunService 转写一致） */
interface StoredFile { name: string; size: number }
interface StoredStep {
  kind?: 'think' | 'text' | 'tool' | 'confirm' | 'param'
  text?: string
  toolCallId?: string
  name?: string
  args?: string
  result?: string
  status?: Step['status']
  replyId?: string
  tools?: ConfirmTool[]
  question?: string
  fields?: ParamField[]
  values?: Record<string, unknown>
}
interface StoredContent {
  text?: string
  steps?: StoredStep[]
  files?: StoredFile[]
  skillKeys?: string[]
  error?: string
  // 旧版落库格式（thinking/tools），仅作兼容回放
  thinking?: string
  tools?: { name?: string; payload?: string }[]
}

function makeStep(partial: Partial<Step>): Step {
  return {
    kind: 'think', text: '', toolCallId: '', name: 'tool',
    args: '', result: '', status: 'done', open: false,
    replyId: '', tools: [], question: '', fields: [], values: {}, ...partial
  }
}

/** 切换会话时回放历史消息（过程容器与步骤默认折叠、工具为完成态） */
async function loadHistory(sessionId: string) {
  messages.splice(0)
  attachments.value = []
  if (!sessionId) return
  let list
  try {
    list = await sessionApi.messages(sessionId)
  } catch {
    return // 历史加载失败不阻塞新对话
  }
  for (const hm of list) {
    let c: StoredContent
    try {
      c = JSON.parse(hm.content) as StoredContent
    } catch {
      continue // 跳过坏数据
    }
    if (hm.role === 'user') {
      messages.push({
        role: 'user',
        text: c.text ?? '',
        files: (c.files ?? []).map((f) => ({ name: f.name, size: formatSize(f.size) })),
        skills: c.skillKeys ?? [],
        steps: [],
        processOpen: false
      })
    } else {
      messages.push({
        role: 'assistant',
        text: c.text ?? '',
        steps: storedSteps(c),
        processOpen: false,
        error: c.error,
        runId: hm.runId
      })
    }
  }
  scrollToBottom()
}

/** 落库步骤 → 页面步骤；兼容旧版 {thinking, tools} 格式 */
function storedSteps(c: StoredContent): Step[] {
  if (c.steps) {
    return c.steps.map((s) => makeStep({
      kind: s.kind ?? 'think',
      text: s.text ?? '',
      toolCallId: s.toolCallId ?? '',
      name: s.name ?? 'tool',
      args: s.args ?? '',
      result: s.result ?? '',
      status: s.status ?? 'done',
      replyId: s.replyId ?? '',
      tools: s.tools ?? [],
      question: s.question ?? '',
      fields: s.fields ?? [],
      values: s.values ?? {}
    }))
  }
  const steps: Step[] = []
  if (c.thinking) steps.push(makeStep({ kind: 'think', text: c.thinking }))
  for (const t of c.tools ?? []) {
    steps.push(makeStep({ kind: 'tool', name: t.name ?? 'tool', result: t.payload ?? '' }))
  }
  return steps
}

/** 会话产物列表（右侧产物面板数据源） */
async function loadArtifacts(sessionId: string) {
  artifacts.value = []
  if (!sessionId) return
  try {
    artifacts.value = await artifactApi.list(sessionId)
  } catch {
    // 产物加载失败不阻塞对话
  }
}

async function downloadArtifact(a: ArtifactItem) {
  try {
    const { url } = await artifactApi.download(a.id)
    window.open(url, '_blank')
  } catch (e: any) {
    toast(e.message ?? '下载失败')
  }
}

function closeModelMenu() {
  modelMenuOpen.value = false
  skillMenuOpen.value = false
  atStart.value = -1
  atKeyword.value = ''
}

async function loadModels() {
  models.value = await modelApi.list()
  if (!modelKey.value || !enabledModels.value.some((m) => m.modelKey === modelKey.value)) {
    modelKey.value = enabledModels.value[0]?.modelKey
  }
}

/** @ 技能选择器：打开时拉取最新列表（轻量，技能不多） */
async function toggleSkillMenu() {
  atStart.value = -1
  atKeyword.value = ''
  skillMenuOpen.value = !skillMenuOpen.value
  if (skillMenuOpen.value) await refreshSkills()
}

async function refreshSkills() {
  try {
    availableSkills.value = await skillApi.list()
  } catch (e: any) {
    // 拉取失败必须可见：静默吞错会让用户误以为没有可用技能
    availableSkills.value = []
    toast(e?.message ?? '技能列表加载失败')
  }
}

/** 菜单展示列表：打字唤起时按 @ 后关键词过滤 skillKey/描述 */
const menuSkills = computed(() => {
  const kw = atKeyword.value.toLowerCase()
  if (atStart.value < 0 || !kw) return availableSkills.value
  return availableSkills.value.filter(
    (s) => s.skillKey.toLowerCase().includes(kw) || (s.description ?? '').toLowerCase().includes(kw))
})

/** 输入时检测 @ 唤起：光标前最近的 @（行首或空白后）且其后无空白 → 打开菜单并按关键词过滤 */
function detectAtMention() {
  const el = textareaRef.value
  if (!el) return
  const pos = el.selectionStart ?? input.value.length
  const at = input.value.lastIndexOf('@', pos - 1)
  // @ 必须在行首或跟在空白后；@ 与光标之间出现空白说明关键词已结束
  const valid = at >= 0 && (at === 0 || /\s/.test(input.value[at - 1]))
  const kw = valid ? input.value.slice(at + 1, pos) : ''
  if (!valid || /\s/.test(kw)) {
    if (atStart.value >= 0) {
      skillMenuOpen.value = false
      atStart.value = -1
      atKeyword.value = ''
    }
    return
  }
  atStart.value = at
  atKeyword.value = kw
  if (!skillMenuOpen.value) {
    skillMenuOpen.value = true
    refreshSkills()
  }
}

function onInput() {
  autoResize()
  detectAtMention()
}

function toggleSkill(s: SkillSummary) {
  const i = selectedSkills.value.findIndex((x) => x.skillKey === s.skillKey)
  if (i >= 0) {
    selectedSkills.value.splice(i, 1)
    return
  }
  selectedSkills.value.push(s)
  // 打字唤起：选中后把输入框里的「@关键词」移除（技能以 chip 形式表达，不留残文）
  if (atStart.value >= 0) {
    const el = textareaRef.value
    const pos = el?.selectionStart ?? input.value.length
    input.value = input.value.slice(0, atStart.value) + input.value.slice(pos)
    nextTick(() => {
      el?.focus()
      el?.setSelectionRange(atStart.value, atStart.value)
    })
    skillMenuOpen.value = false
    atStart.value = -1
    atKeyword.value = ''
  }
}

function isSkillSelected(s: SkillSummary): boolean {
  return selectedSkills.value.some((x) => x.skillKey === s.skillKey)
}

function pickModel(key: string) {
  modelKey.value = key
  modelMenuOpen.value = false
}

function goAddModel() {
  modelMenuOpen.value = false
  router.push({ path: '/settings', query: { tab: 'models', action: 'add' } })
}

function formatSize(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)}MB` : `${(bytes / 1024).toFixed(0)}KB`
}

function formatTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

async function onFiles(event: Event) {
  const inputEl = event.target as HTMLInputElement
  for (const file of Array.from(inputEl.files ?? [])) {
    try {
      attachments.value.push(await fileApi.upload(file))
    } catch (e: any) {
      toast(e.message ?? `上传失败：${file.name}`)
    }
  }
  inputEl.value = ''
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

function currentAssistant(): ChatMessage {
  return messages[messages.length - 1]
}

async function send() {
  if (!canSend.value || running.value) return
  const text = input.value.trim()
  const skillKeys = selectedSkills.value.map((s) => s.skillKey)
  input.value = ''
  closeModelMenu()
  nextTick(autoResize)
  messages.push({
    role: 'user',
    text,
    files: attachments.value.map((f) => ({ name: f.filename, size: formatSize(f.size) })),
    skills: skillKeys,
    steps: [],
    processOpen: false
  })
  messages.push({
    role: 'assistant',
    text: '',
    steps: [],
    processOpen: true,
    streaming: true
  })
  running.value = true
  abortController = new AbortController()
  scrollToBottom()

  try {
    await streamRun(
      {
        sessionId: chat.currentSessionId,
        message: text,
        fileIds: attachments.value.map((f) => f.fileId),
        modelKey: modelKey.value,
        skillKeys: skillKeys.length ? skillKeys : undefined
      },
      handleEvent,
      abortController.signal
    )
    attachments.value = []
    selectedSkills.value = []
    chat.refresh() // 首轮对话后后端会自动生成会话标题
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      currentAssistant().error = e.message ?? '连接失败'
    }
  } finally {
    finishMessage(currentAssistant(), true)
    running.value = false
    scrollToBottom()
  }
}

/**
 * HITL 确认：允许/拒绝后走 resume SSE 流，事件继续追加到当前 assistant 消息。
 * 同一确认卡内的多个工具调用应用同一决定（M2 语义：一次确认整批）。
 */
async function confirmResolve(m: ChatMessage, s: Step, approved: boolean) {
  const runId = currentRunId.value || m.runId
  if (resuming.value || !runId || s.status !== 'pending') {
    if (!runId) toast('会话状态已过期，请重新发起任务')
    return
  }
  currentRunId.value = runId
  resuming.value = true
  running.value = true
  m.streaming = true
  abortController = new AbortController()
  scrollToBottom()
  try {
    await streamResume(
      runId,
      s.tools.map((t) => ({ toolCallId: t.id, approved })),
      handleEvent,
      abortController.signal
    )
    chat.refresh()
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      m.error = e.message ?? '连接失败'
      toast(m.error ?? '连接失败')
      // resume 失败（如快照过期）：确认卡置为拒绝态，避免反复点击
      s.status = 'rejected'
    }
  } finally {
    finishMessage(m, true)
    resuming.value = false
    running.value = false
    scrollToBottom()
  }
}

/**
 * 参数补全：校验必填 → 提交 answer SSE 流，事件继续追加到当前 assistant 消息。
 * 提交后表单卡置 resolved（输入框只读），提交失败回退 pending 允许重试。
 */
async function submitAnswer(m: ChatMessage, s: Step) {
  const runId = currentRunId.value || m.runId
  if (resuming.value || !runId || s.status !== 'pending') {
    if (!runId) toast('会话状态已过期，请重新发起任务')
    return
  }
  const missing = s.fields.filter(
    (f) => f.required && (s.values[f.key] === '' || s.values[f.key] == null)
  )
  if (missing.length > 0) {
    toast(`请先填写：${missing.map((f) => f.label || f.key).join('、')}`)
    return
  }
  currentRunId.value = runId
  resuming.value = true
  running.value = true
  m.streaming = true
  abortController = new AbortController()
  scrollToBottom()
  try {
    await streamAnswer(runId, s.toolCallId, s.values, handleEvent, abortController.signal)
    s.status = 'resolved'
    chat.refresh()
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      m.error = e.message ?? '连接失败'
      toast(m.error ?? '连接失败')
      // 提交失败（如快照过期）保持 pending 文案提示；卡片输入仍可编辑重试
    }
  } finally {
    finishMessage(m, true)
    resuming.value = false
    running.value = false
    scrollToBottom()
  }
}

async function stop() {
  abortController?.abort()
  if (currentRunId.value) {
    try {
      await fetch(`/api/runs/${currentRunId.value}/stop`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${getToken()}` }
      })
    } catch {
      /* 忽略 */
    }
  }
}

function handleEvent(envelope: SseEnvelope) {
  const m = currentAssistant()
  const data = envelope.data as Record<string, unknown>
  const rawType = typeof data?.type === 'string' ? data.type : ''
  const delta = typeof data?.delta === 'string' ? data.delta : ''
  switch (envelope.event) {
    case EVT.RUN_START:
      currentRunId.value = envelope.runId
      break
    case EVT.THINKING_DELTA:
      if (rawType === RAW.THINKING_START) {
        // 思考步骤默认折叠为一行（「正在深度思考…」），点击展开看内容
        pushStep(m, makeStep({ kind: 'think' }))
      } else if (delta) {
        lastThinkStep(m).text += delta
      }
      break
    case EVT.TEXT_DELTA:
      if (rawType === RAW.TEXT_START) {
        flushText(m)
      } else {
        m.text += delta
      }
      break
    case EVT.TOOL_CALL: {
      const toolCallId = String(data?.toolCallId ?? '')
      if (rawType === RAW.TOOL_CALL_START) {
        flushText(m)
        pushStep(m, makeStep({
          kind: 'tool', toolCallId, name: String(data?.toolCallName ?? 'tool'),
          status: 'running'
        }))
      } else if (rawType === RAW.TOOL_CALL_DELTA) {
        const s = findTool(m, toolCallId)
        if (s && delta) s.args = clip(s.args + delta)
      } else if (rawType === RAW.TOOL_CALL_END) {
        const s = findTool(m, toolCallId)
        if (s) s.args = prettyJson(s.args)
      }
      break
    }
    case EVT.TOOL_RESULT: {
      const toolCallId = String(data?.toolCallId ?? '')
      let s = findTool(m, toolCallId)
      if (!s) {
        // resume 续跑不重发 TOOL_CALL_START，被放行工具以结果事件先出现，补建工具卡
        flushText(m)
        s = makeStep({
          kind: 'tool', toolCallId, name: String(data?.toolCallName ?? 'tool'),
          status: 'running'
        })
        pushStep(m, s)
      }
      if (rawType === RAW.TOOL_RESULT_END) {
        s.status = 'done'
        s.open = false
      } else if (delta) {
        s.result = clip(s.result + delta)
      }
      break
    }
    case EVT.HITL_CONFIRM: {
      // run 挂起等待确认：推确认卡（replyId 关联 resume 后的 resolved 事件）
      flushText(m)
      const toolCalls = Array.isArray(data?.toolCalls) ? data.toolCalls : []
      pushStep(m, makeStep({
        kind: 'confirm',
        replyId: String(data?.replyId ?? ''),
        status: 'pending',
        tools: toolCalls.map((t: any) => ({
          id: String(t?.id ?? ''),
          name: String(t?.name ?? 'tool'),
          args: typeof t?.input === 'object' ? JSON.stringify(t.input) : ''
        }))
      }))
      break
    }
    case EVT.HITL_CONFIRM_RESOLVED: {
      const s = findConfirm(m, String(data?.replyId ?? ''))
      if (s) {
        const results = Array.isArray(data?.confirmResults) ? data.confirmResults : []
        const allApproved = results.length > 0 && results.every((r: any) => r?.confirmed)
        s.status = allApproved ? 'approved' : 'rejected'
      }
      break
    }
    case EVT.HITL_ASK_PARAM: {
      // ask_user 挂起：取第一个挂起工具调用的入参（question + fields）建表单卡
      flushText(m)
      const toolCalls = Array.isArray(data?.toolCalls) ? data.toolCalls : []
      const first: any = toolCalls[0] ?? {}
      const input = typeof first?.input === 'object' && first.input ? first.input : {}
      const fields: ParamField[] = (Array.isArray(input.fields) ? input.fields : []).map((f: any) => ({
        key: String(f?.key ?? ''),
        label: String(f?.label ?? f?.key ?? ''),
        type: (['textarea', 'number', 'select'].includes(f?.type) ? f.type : 'text') as ParamField['type'],
        required: !!f?.required,
        placeholder: typeof f?.placeholder === 'string' ? f.placeholder : '',
        options: Array.isArray(f?.options) ? f.options.map(String) : []
      })).filter((f: ParamField) => f.key)
      // 预填默认值（select 默认第一个选项之外保持空，交由用户选择）
      const values: Record<string, unknown> = {}
      fields.forEach((f) => { values[f.key] = '' })
      pushStep(m, makeStep({
        kind: 'param',
        toolCallId: String(first?.id ?? ''),
        question: String(input.question ?? ''),
        fields,
        values,
        status: 'pending',
        open: true
      }))
      break
    }
    case EVT.HITL_PARAM_RESOLVED: {
      // 提交结果回执：answer 续跑流里把旧表单卡置为已提交（合并落库时后端同样回填）
      const results = Array.isArray(data?.toolResults) ? data.toolResults : []
      for (const r of results) {
        const s = findParam(m, String((r as any)?.id ?? ''))
        if (s && s.status === 'pending') s.status = 'resolved'
      }
      break
    }
    case EVT.ARTIFACT_CREATED: {
      artifacts.value.unshift({
        id: Number(data?.artifactId),
        fileName: String(data?.fileName ?? '产物'),
        size: Number(data?.size ?? 0),
        contentType: typeof data?.contentType === 'string' ? data.contentType : undefined,
        runId: envelope.runId,
        createdAt: new Date().toISOString()
      })
      toast(`📦 新产物：${String(data?.fileName ?? '')}`)
      break
    }
    case EVT.RUN_END:
      finishMessage(m, true)
      break
    case EVT.RUN_ERROR:
      m.error = String(data?.message ?? '运行出错')
      finishMessage(m, true)
      break
  }
  scrollToBottom()
}

/** 新步骤入列：进行中保留所有步骤展开，便于用户边看边推；run 终态再统一收口 */
function pushStep(m: ChatMessage, step: Step) {
  m.steps.push(step)
}

/** 思考分片追加到最近的思考步骤；不存在则新建 */
function lastThinkStep(m: ChatMessage): Step {
  const last = m.steps[m.steps.length - 1]
  if (last && last.kind === 'think') return last
  const step = makeStep({ kind: 'think' })
  pushStep(m, step)
  return step
}

/** 当前文本块冲入步骤流（工具调用前/新文本块开始前的中间结论），与后端 Transcript.flushText 一致 */
function flushText(m: ChatMessage) {
  if (m.text) {
    pushStep(m, makeStep({ kind: 'text', text: m.text }))
    m.text = ''
  }
}

/** 按 toolCallId 倒序查找工具步骤 */
function findTool(m: ChatMessage, toolCallId: string): Step | undefined {
  for (let i = m.steps.length - 1; i >= 0; i--) {
    const s = m.steps[i]
    if (s.kind === 'tool' && s.toolCallId === toolCallId) return s
  }
  return undefined
}

/** 按 replyId 倒序查找确认步骤 */
function findConfirm(m: ChatMessage, replyId: string): Step | undefined {
  for (let i = m.steps.length - 1; i >= 0; i--) {
    const s = m.steps[i]
    if (s.kind === 'confirm' && s.replyId === replyId) return s
  }
  return undefined
}

/** 按 toolCallId 倒序查找参数表单卡 */
function findParam(m: ChatMessage, toolCallId: string): Step | undefined {
  for (let i = m.steps.length - 1; i >= 0; i--) {
    const s = m.steps[i]
    if (s.kind === 'param' && s.toolCallId === toolCallId) return s
  }
  return undefined
}

function closeSteps(m: ChatMessage) {
  for (const s of m.steps) s.open = false
}

/**
 * 流结束收口：fold=true 才会折叠过程容器与所有步骤（仅 run 真完成 / 报错时调用）；
 * fold=false 时仅结束 streaming 标记（保留展开态），挂起确认 / 参数补全用此模式。
 * 运行中的工具统一置为完成。
 */
function finishMessage(m: ChatMessage, fold: boolean) {
  m.streaming = false
  if (fold) {
    m.processOpen = false
    closeSteps(m)
  }
  for (const s of m.steps) {
    if (s.kind === 'tool' && s.status === 'running') s.status = 'done'
  }
}

function clip(s: string): string {
  return s.length > PAYLOAD_MAX ? s.slice(0, PAYLOAD_MAX) + '…' : s
}

/** 工具参数分片拼接完成后尝试格式化为 JSON（非 JSON 保留原文） */
function prettyJson(raw: string): string {
  if (!raw.trim()) return raw
  try {
    return clip(JSON.stringify(JSON.parse(raw), null, 2))
  } catch {
    return raw
  }
}

/** 工具行参数摘要：记忆工具取语义化字段，其余优先 JSON 首个字符串值（如 command），否则原文单行截断 */
function toolBrief(s: Step): string {
  const raw = s.args.trim()
  if (!raw) return ''
  let brief = raw
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>
    if (s.name === 'memory_save' && typeof obj.content === 'string') {
      // 取 content 首个 bullet（去 -/* 前缀）
      brief = obj.content
        .split('\n')
        .map((l) => l.replace(/^[-*]\s*/, '').trim())
        .find((l) => l) ?? obj.content
    } else if ((s.name === 'memory_search' || s.name === 'session_search') && typeof obj.query === 'string') {
      brief = obj.query
    } else if (s.name === 'memory_get' && typeof obj.path === 'string') {
      brief = obj.path
    } else {
      const first = Object.values(obj).find((v) => typeof v === 'string')
      if (typeof first === 'string') brief = first
    }
  } catch {
    // 参数分片未完整，用原文
  }
  brief = brief.replace(/\s+/g, ' ').trim()
  return brief.length > BRIEF_MAX ? brief.slice(0, BRIEF_MAX) + '…' : brief
}

function scrollToBottom() {
  nextTick(() => {
    if (chatScrollRef.value) chatScrollRef.value.scrollTop = chatScrollRef.value.scrollHeight
  })
}
</script>
