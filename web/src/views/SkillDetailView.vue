<!-- @author 辰夕 -->
<template>
  <div class="main">
    <div class="sd-head">
      <button class="sd-back" @click="router.push('/skills')">← 返回列表</button>
      <span class="sic" :style="{ background: skillBg(detail?.skillKey ?? '') }">
        {{ skillIcon(detail?.skillKey ?? '') }}
      </span>
      <div class="sd-title">
        <h3>{{ detail?.skillKey ?? '…' }}</h3>
        <div class="sub">更新于 {{ formatTime(detail?.updatedAt) }} · {{ formatSize(detail?.totalSize) }}</div>
      </div>
      <span v-if="detail" :class="['scope-badge', detail.scope === 'PUBLIC' ? 'pub' : 'pri']">
        {{ detail.scope === 'PUBLIC' ? '公共技能' : '私人技能' }}
      </span>
      <div v-if="detail && canManage" class="sd-ops">
        <button @click="onExport">⬇ 导出</button>
        <button class="danger" @click="onDelete">🗑 删除</button>
      </div>
    </div>
    <div class="sd-body">
      <div class="fx-tree">
        <div class="tree-title">📦 {{ detail?.skillKey }}</div>
        <template v-for="node in tree" :key="node.name">
          <tree-node :node="node" :selected="currentPath" @select="selectFile" />
        </template>
      </div>
      <div class="fx-view">
        <template v-if="currentPath">
          <div class="fx-path">{{ detail?.skillKey }}/{{ currentPath }}</div>
          <pre v-if="currentPath.endsWith('.md')" class="md-view">{{ fileContent }}</pre>
          <pre v-else class="code-view">{{ fileContent }}</pre>
        </template>
        <div v-else class="fx-empty">👈 点击左侧文件查看内容</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, type PropType } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { skillApi, type SkillDetail } from '../api'
import { useAuthStore } from '../stores/auth'
import { skillBg, skillIcon } from '../utils/skillMeta'
import { toast } from '../utils/toast'
import { confirmDialog } from '../utils/confirm'

/** 文件树节点：目录有 children，文件有 path */
interface TreeNode {
  name: string
  path?: string
  children: TreeNode[]
}

function fileIcon(name: string): string {
  if (name.endsWith('.md')) return '📄'
  if (name.endsWith('.json')) return '🧾'
  if (name.endsWith('.py')) return '🐍'
  if (name.endsWith('.sh')) return '⚙️'
  return '📃'
}

/** 递归树节点组件（本文件内定义，配合 JSX-free render 函数） */
const TreeNode = (props: { node: TreeNode; selected: string }, { emit }: any) => {
  const n = props.node
  if (n.path) {
    return h('div', {
      class: ['ti', { sel: props.selected === n.path }],
      onClick: () => emit('select', n.path)
    }, [h('span', { class: 'fi' }, fileIcon(n.name)), n.name])
  }
  const open = ref(true)
  return h('div', [
    h('div', {
      class: 'ti folder',
      onClick: () => { open.value = !open.value }
    }, [h('span', { class: 'fi' }, open.value ? '▾' : '▸'), `📁 ${n.name}`]),
    open.value
      ? h('div', { class: 'ti-children' }, n.children.map((c) => h(TreeNode as any, {
          node: c, selected: props.selected, onSelect: (p: string) => emit('select', p), key: c.name
        })))
      : null
  ])
}
TreeNode.props = { node: { type: Object as PropType<TreeNode>, required: true }, selected: String }
TreeNode.emits = ['select']

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const detail = ref<SkillDetail>()
const currentPath = ref('')
const fileContent = ref('')

const skillId = Number(route.params.id)

/** 权限矩阵（与原型一致）：私人技能属主可导出/删除；公共技能仅管理员 */
const canManage = computed(() =>
  !!detail.value && (detail.value.mine || auth.profile?.role === 'ADMIN')
)

const tree = computed<TreeNode[]>(() => buildTree(detail.value?.files ?? []))

onMounted(async () => {
  if (!auth.profile) await auth.fetchProfile()
  try {
    detail.value = await skillApi.detail(skillId)
    const first = detail.value.files.find((f) => f === 'SKILL.md') ?? detail.value.files[0]
    if (first) await selectFile(first)
  } catch (e: any) {
    toast(e.message ?? '技能加载失败')
    router.push('/skills')
  }
})

function buildTree(files: string[]): TreeNode[] {
  const root: TreeNode = { name: '', children: [] }
  for (const path of files) {
    const parts = path.split('/')
    let node = root
    parts.forEach((p, i) => {
      if (i === parts.length - 1) {
        node.children.push({ name: p, path, children: [] })
      } else {
        let child = node.children.find((c) => !c.path && c.name === p)
        if (!child) {
          child = { name: p, children: [] }
          node.children.push(child)
        }
        node = child
      }
    })
  }
  // 目录在前、文件在后，各自按名排序
  const sortNodes = (nodes: TreeNode[]): TreeNode[] =>
    nodes.sort((a, b) => {
      const af = !!a.path, bf = !!b.path
      if (af !== bf) return af ? 1 : -1
      return a.name.localeCompare(b.name)
    })
  const walk = (n: TreeNode) => { n.children = sortNodes(n.children); n.children.forEach(walk) }
  walk(root)
  return root.children
}

async function selectFile(path: string) {
  currentPath.value = path
  fileContent.value = '加载中…'
  try {
    const resp = await skillApi.fileContent(skillId, path)
    fileContent.value = resp.content
  } catch (e: any) {
    fileContent.value = `⚠ ${e.message ?? '文件读取失败'}`
  }
}

async function onExport() {
  try {
    const { url } = await skillApi.exportUrl(skillId)
    window.open(url, '_blank')
  } catch (e: any) {
    toast(e.message ?? '导出失败')
  }
}

async function onDelete() {
  if (!(await confirmDialog(`确定删除技能「${detail.value?.skillKey}」？删除后不可恢复。`, { title: '删除技能', confirmText: '删除' }))) return
  try {
    await skillApi.remove(skillId)
    toast('技能已删除')
    router.push('/skills')
  } catch (e: any) {
    toast(e.message ?? '删除失败')
  }
}

function formatTime(iso?: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

function formatSize(bytes?: number): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>
