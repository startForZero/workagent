<!-- @author 辰夕 -->
<template>
  <div class="rich-text">
    <template v-for="(part, i) in parts" :key="i">
      <div v-if="part.type === 'md'" class="md" v-html="renderMarkdown(part.content)" />
      <div v-else class="hp-card">
        <div class="hp-bar">
          <span>🧾 HTML 报告</span>
          <span class="hp-spacer" />
          <button class="hp-btn" @click="showSourceSet.has(i) ? showSourceSet.delete(i) : showSourceSet.add(i)">
            {{ showSourceSet.has(i) ? '预览' : '源码' }}
          </button>
        </div>
        <!-- sandbox=allow-scripts：报告里的 echarts 等脚本能跑，但 opaque origin 摸不到本站存储 -->
        <iframe v-if="!showSourceSet.has(i)" class="hp-frame" :srcdoc="part.content"
                sandbox="allow-scripts" title="HTML 报告预览" />
        <pre v-else class="hp-src"><code>{{ part.content }}</code></pre>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { renderMarkdown } from '../utils/markdown'

/**
 * 富文本渲染：助手消息正文 / 阶段结论。
 * 普通内容走 Markdown；检测到完整 HTML 文档（```html 围栏或裸 <!DOCTYPE html>）时
 * 拆出为预览卡，iframe srcdoc 沙箱渲染，可切换查看源码。
 */
const props = defineProps<{ text: string }>()

interface Part {
  type: 'md' | 'html'
  content: string
}

/** 「完整 HTML 文档」判定：含 doctype 或 <html> 根标签（短片段 <div>xx</div> 不算） */
const HTML_DOC_RE = /<!doctype\s+html|<html[\s>]/i
/** ```lang ... ``` 围栏（未闭合的流式片段不匹配，自然落到 md 里继续展示代码） */
const FENCE_RE = /```(\w*)\s*\n([\s\S]*?)```/g
/** 裸 HTML 文档：<!doctype html ... </html> */
const RAW_DOC_RE = /<!doctype\s+html[\s\S]*?<\/html\s*>/gi

/** 预览卡「显示源码」开关（按 index 记录，流式追加时 index 稳定） */
const showSourceSet = reactive(new Set<number>())

const parts = computed<Part[]>(() => splitParts(props.text))

function splitParts(text: string): Part[] {
  const out: Part[] = []
  let last = 0
  for (const m of text.matchAll(FENCE_RE)) {
    const lang = (m[1] ?? '').toLowerCase()
    if (lang === 'html' && HTML_DOC_RE.test(m[2])) {
      pushMd(out, text.slice(last, m.index))
      out.push({ type: 'html', content: m[2] })
    } else {
      // 非 html 围栏 / 非完整文档：原样保留给 Markdown 渲染代码块
      pushMd(out, text.slice(last, (m.index ?? 0) + m[0].length))
    }
    last = (m.index ?? 0) + m[0].length
  }
  pushMd(out, text.slice(last))
  return out.filter((p) => p.type === 'html' || p.content.trim())
}

/** md 段入列前再扫一遍裸 HTML 文档（模型有时不写围栏直接吐整页） */
function pushMd(out: Part[], text: string) {
  if (!text.trim()) return
  let last = 0
  RAW_DOC_RE.lastIndex = 0
  for (const m of text.matchAll(RAW_DOC_RE)) {
    const before = text.slice(last, m.index)
    if (before.trim()) out.push({ type: 'md', content: before })
    out.push({ type: 'html', content: m[0] })
    last = (m.index ?? 0) + m[0].length
  }
  const tail = text.slice(last)
  if (tail.trim()) out.push({ type: 'md', content: tail })
}
</script>
