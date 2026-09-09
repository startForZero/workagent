<!-- @author 辰夕 -->
<template>
  <div class="main">
    <div class="topbar">
      {{ currentTitle }}
      <span v-if="running" class="status-pill">⏳ 小梓生成中…</span>
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
            <!-- 用户消息：附件 chips + 气泡 -->
            <template v-if="m.role === 'user'">
              <div class="bubble-user">
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
                    <!-- 阶段结论（工具之间的中间文本块）：直接渲染 -->
                    <div v-else-if="s.kind === 'text'" class="prow-text md" v-html="renderMarkdown(s.text)" />
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

              <div v-if="m.text" class="msg-text md" :class="{ cursor: m.streaming }"
                   v-html="renderMarkdown(m.text)" />
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
          <div v-for="(f, i) in attachments" :key="f.fileId" class="chip">
            📎 <b>{{ f.filename }}</b><span>{{ formatSize(f.size) }}</span>
            <i @click="attachments.splice(i, 1)">✕</i>
          </div>
        </div>
        <div class="input-box">
          <textarea ref="textareaRef" v-model="input" rows="2"
                    placeholder="输入消息，@ 唤起技能…"
                    @input="autoResize" @keydown.enter.exact.prevent="send" />
          <div class="input-toolbar">
            <button class="tool-btn" title="上传文件" @click="fileInputRef?.click()">📎</button>
            <input ref="fileInputRef" type="file" multiple style="display: none" @change="onFiles" />
            <button class="tool-btn" title="@ 技能" @click="toast('@ 唤起技能将在 M3 上线')">@</button>
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
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { fileApi, modelApi, sessionApi, type UploadedFile, type UserModel } from '../api'
import { streamRun, type SseEnvelope } from '../api/sse'
import { getToken } from '../router'
import { providerMeta } from '../constants/providers'
import { toolLabel } from '../constants/tools'
import { useChatStore } from '../stores/chat'
import { renderMarkdown } from '../utils/markdown'
import { toast } from '../utils/toast'

/** SSE 事件名契约（与后端 SseEventType 对应） */
const EVT = {
  RUN_START: 'run.start',
  THINKING_DELTA: 'thinking.delta',
  TEXT_DELTA: 'text.delta',
  TOOL_CALL: 'tool.call',
  TOOL_RESULT: 'tool.result',
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

/**
 * 过程步骤（WorkBuddy 风格行内步骤行）：
 * think=思考，text=阶段结论（工具之间的中间文本块），tool=工具调用
 */
interface Step {
  kind: 'think' | 'text' | 'tool'
  text: string
  toolCallId: string
  name: string
  args: string
  result: string
  status: 'running' | 'done'
  open: boolean
}

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  files?: { name: string; size: string }[]
  steps: Step[]
  processOpen: boolean
  error?: string
  streaming?: boolean
}

const router = useRouter()
const chat = useChatStore()

const messages = reactive<ChatMessage[]>([])
const input = ref('')
const running = ref(false)
const attachments = ref<UploadedFile[]>([])
const models = ref<UserModel[]>([])
const modelKey = ref<string>()
const modelMenuOpen = ref(false)
const chatScrollRef = ref<HTMLElement>()
const textareaRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const currentRunId = ref('')
let abortController: AbortController | null = null

const enabledModels = computed(() => models.value.filter((m) => m.enabled))
const currentModelLabel = computed(() => {
  const m = models.value.find((x) => x.modelKey === modelKey.value)
  return m ? `${providerMeta(m.provider).name} · ${m.model}` : '选择模型'
})
const currentTitle = computed(
  () => chat.sessions.find((s) => s.sessionId === chat.currentSessionId)?.title ?? '新对话'
)
const canSend = computed(() => input.value.trim().length > 0 && !!chat.currentSessionId)

onMounted(async () => {
  document.addEventListener('click', closeModelMenu)
  await loadModels()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeModelMenu)
  abortController?.abort()
})

watch(
  () => chat.currentSessionId,
  (sessionId) => {
    if (running.value) stop()
    loadHistory(sessionId)
  },
  { immediate: true }
)

/** 历史消息落库结构（与后端 RunService 转写一致） */
interface StoredFile { name: string; size: number }
interface StoredStep {
  kind?: 'think' | 'text' | 'tool'
  text?: string
  name?: string
  args?: string
  result?: string
  status?: 'running' | 'done'
}
interface StoredContent {
  text?: string
  steps?: StoredStep[]
  files?: StoredFile[]
  error?: string
  // 旧版落库格式（thinking/tools），仅作兼容回放
  thinking?: string
  tools?: { name?: string; payload?: string }[]
}

function makeStep(partial: Partial<Step>): Step {
  return {
    kind: 'think', text: '', toolCallId: '', name: 'tool',
    args: '', result: '', status: 'done', open: false, ...partial
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
        steps: [],
        processOpen: false
      })
    } else {
      messages.push({
        role: 'assistant',
        text: c.text ?? '',
        steps: storedSteps(c),
        processOpen: false,
        error: c.error
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
      name: s.name ?? 'tool',
      args: s.args ?? '',
      result: s.result ?? '',
      status: s.status ?? 'done'
    }))
  }
  const steps: Step[] = []
  if (c.thinking) steps.push(makeStep({ kind: 'think', text: c.thinking }))
  for (const t of c.tools ?? []) {
    steps.push(makeStep({ kind: 'tool', name: t.name ?? 'tool', result: t.payload ?? '' }))
  }
  return steps
}

function closeModelMenu() {
  modelMenuOpen.value = false
}

async function loadModels() {
  models.value = await modelApi.list()
  if (!modelKey.value || !enabledModels.value.some((m) => m.modelKey === modelKey.value)) {
    modelKey.value = enabledModels.value[0]?.modelKey
  }
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
  input.value = ''
  nextTick(autoResize)
  messages.push({
    role: 'user',
    text,
    files: attachments.value.map((f) => ({ name: f.filename, size: formatSize(f.size) })),
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
        modelKey: modelKey.value
      },
      handleEvent,
      abortController.signal
    )
    attachments.value = []
    chat.refresh() // 首轮对话后后端会自动生成会话标题
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      currentAssistant().error = e.message ?? '连接失败'
    }
  } finally {
    finishMessage(currentAssistant())
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
        pushStep(m, makeStep({ kind: 'think', open: true }))
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
          status: 'running', open: true
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
      const s = findTool(m, String(data?.toolCallId ?? ''))
      if (!s) break
      if (rawType === RAW.TOOL_RESULT_END) {
        s.status = 'done'
        s.open = false
      } else if (delta) {
        s.result = clip(s.result + delta)
      }
      break
    }
    case EVT.RUN_END:
      finishMessage(m)
      break
    case EVT.RUN_ERROR:
      m.error = String(data?.message ?? '运行出错')
      finishMessage(m)
      break
  }
  scrollToBottom()
}

/** 新步骤入列：上一步骤自动折叠（保持「当前活动步骤展开」的 WorkBuddy 行为） */
function pushStep(m: ChatMessage, step: Step) {
  closeSteps(m)
  m.steps.push(step)
}

/** 思考分片追加到最近的思考步骤；不存在则新建 */
function lastThinkStep(m: ChatMessage): Step {
  const last = m.steps[m.steps.length - 1]
  if (last && last.kind === 'think') return last
  const step = makeStep({ kind: 'think', open: true })
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

function closeSteps(m: ChatMessage) {
  for (const s of m.steps) s.open = false
}

/** run 结束/出错/停止：折叠过程容器与所有步骤，运行中的工具置为完成 */
function finishMessage(m: ChatMessage) {
  m.streaming = false
  m.processOpen = false
  closeSteps(m)
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

/** 工具行参数摘要：优先取 JSON 首个字符串值（如 command），否则用原文单行截断 */
function toolBrief(s: Step): string {
  const raw = s.args.trim()
  if (!raw) return ''
  let brief = raw
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>
    const first = Object.values(obj).find((v) => typeof v === 'string')
    if (typeof first === 'string') brief = first
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
