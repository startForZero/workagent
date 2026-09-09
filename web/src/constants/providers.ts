/**
 * @author 辰夕
 */
/**
 * 服务商展示元数据（颜色/字母/默认接入点），与原型 PROVIDERS 一致。
 * 仅用于前端展示；可用性以后端 /api/models/providers 返回为准。
 */
export interface ProviderMeta {
  id: string
  name: string
  color: string
  letter: string
  baseUrl: string
  model: string
}

export const PROVIDERS: ProviderMeta[] = [
  { id: 'deepseek', name: 'DeepSeek', color: '#4d6bfe', letter: 'D', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { id: 'kimi', name: 'Kimi', color: '#0f172a', letter: 'K', baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-k2' },
  { id: 'minimax', name: 'MiniMax', color: '#e11d48', letter: 'M', baseUrl: 'https://api.minimaxi.com/v1', model: 'MiniMax-M2' },
  { id: 'dashscope', name: '通义千问', color: '#7c3aed', letter: 'Q', baseUrl: 'https://dashscope.aliyuncs.com/v1', model: 'qwen-max' },
  { id: 'openai', name: 'OpenAI', color: '#10a37f', letter: 'O', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o' },
  { id: 'custom', name: '自定义', color: '#64748b', letter: '+', baseUrl: '', model: '' }
]

const FALLBACK = PROVIDERS[PROVIDERS.length - 1]

export function providerMeta(id: string): ProviderMeta {
  return PROVIDERS.find((p) => p.id === id) ?? FALLBACK
}
