/**
 * @author 辰夕
 */
import { http } from './http'

// ---------- 类型（与后端 DTO 对应） ----------
export interface AuthResponse {
  token: string
  userId: number
  nickname: string
  avatarUrl?: string
}

export interface Profile {
  userId: number
  email: string
  nickname: string
  avatarUrl?: string
  bio?: string
  /** USER / ADMIN（技能公共区管理入口判断） */
  role?: string
}

export interface ProviderTemplate {
  provider: string
  label: string
  defaultBaseUrl: string
  recommendedModel: string
}

export interface UserModel {
  id: number
  provider: string
  model: string
  modelKey: string
  baseUrl?: string
  enabled: boolean
  createdAt: string
}

export interface SessionItem {
  sessionId: string
  title: string
  createdAt: string
  updatedAt: string
}

/** 历史消息（content 为 JSON 字符串，结构见后端 MessageDO 注释；runId 供 HITL 挂起消息 resume） */
export interface HistoryMessage {
  role: 'user' | 'assistant'
  content: string
  runId: string
  createdAt: string
}

export interface UploadedFile {
  fileId: string
  filename: string
  size: number
  contentType?: string
}

/** 沙箱产物（右侧产物面板数据源，与后端 ArtifactResponse 对应） */
export interface ArtifactItem {
  id: number
  fileName: string
  size: number
  contentType?: string
  runId: string
  createdAt: string
}

// ---------- API ----------
export const authApi = {
  register: (nickname: string, email: string, password: string) =>
    http.post<AuthResponse>('/api/auth/register', { nickname, email, password }),
  login: (email: string, password: string) =>
    http.post<AuthResponse>('/api/auth/login', { email, password })
}

export const userApi = {
  me: () => http.get<Profile>('/api/users/me'),
  update: (body: { nickname?: string; avatarUrl?: string; bio?: string }) =>
    http.put<Profile>('/api/users/me', body),
  changePassword: (currentPassword: string, newPassword: string) =>
    http.put<void>('/api/users/me/password', { currentPassword, newPassword })
}

export const modelApi = {
  providers: () => http.get<ProviderTemplate[]>('/api/models/providers'),
  list: () => http.get<UserModel[]>('/api/models'),
  create: (body: { provider: string; model: string; baseUrl?: string; apiKey: string }) =>
    http.post<UserModel>('/api/models', body),
  test: (body: { provider: string; model: string; baseUrl?: string; apiKey: string }) =>
    http.post<void>('/api/models/test', body),
  setEnabled: (id: number, enabled: boolean) =>
    http.put<void>(`/api/models/${id}?enabled=${enabled}`),
  remove: (id: number) => http.del<void>(`/api/models/${id}`)
}

export const sessionApi = {
  create: (title?: string) => http.post<SessionItem>('/api/sessions', { title }),
  list: () => http.get<SessionItem[]>('/api/sessions'),
  messages: (sessionId: string) => http.get<HistoryMessage[]>(`/api/sessions/${sessionId}/messages`),
  remove: (sessionId: string) => http.del<void>(`/api/sessions/${sessionId}`)
}

export const runApi = {
  stop: (runId: string) => http.post<void>(`/api/runs/${runId}/stop`)
}

export const artifactApi = {
  list: (sessionId: string) => http.get<ArtifactItem[]>(`/api/sessions/${sessionId}/artifacts`),
  download: (id: number) => http.get<{ url: string }>(`/api/artifacts/${id}/download`)
}

export const fileApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<UploadedFile>('/api/files', form)
  }
}

// ---------- 技能市场（M3） ----------
export interface SkillSummary {
  id: number
  skillKey: string
  scope: 'PUBLIC' | 'USER'
  description: string
  tags: string[]
  fileCount: number
  totalSize: number
  mine: boolean
  updatedAt: string
}

export interface SkillDetail extends Omit<SkillSummary, 'fileCount'> {
  files: string[]
}

export const skillApi = {
  list: (scope?: string, keyword?: string) => {
    const params = new URLSearchParams()
    if (scope) params.set('scope', scope)
    if (keyword) params.set('keyword', keyword)
    const qs = params.toString()
    return http.get<SkillSummary[]>(`/api/skills${qs ? `?${qs}` : ''}`)
  },
  detail: (id: number) => http.get<SkillDetail>(`/api/skills/${id}`),
  fileContent: (id: number, path: string) =>
    http.get<{ path: string; content: string }>(
      `/api/skills/${id}/files/content?path=${encodeURIComponent(path)}`),
  importZip: (file: File, scope: 'PUBLIC' | 'USER') => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<SkillSummary>(`/api/skills/import?scope=${scope}`, form)
  },
  exportUrl: (id: number) => http.get<{ url: string }>(`/api/skills/${id}/export`),
  remove: (id: number) => http.del<void>(`/api/skills/${id}`)
}

// ---------- 记忆中心（M4） ----------
/** 单条长期记忆（MEMORY.md 的 bullet 条目；sourceSessionTitle 为空表示归纳条目或来源会话已删） */
export interface MemoryItem {
  id: number
  content: string
  sourceSessionId?: number
  sourceSessionTitle?: string
  createdAt: string
  updatedAt: string
}

export const memoryApi = {
  list: () => http.get<MemoryItem[]>('/api/memories'),
  update: (id: number, content: string) => http.put<void>(`/api/memories/${id}`, { content }),
  remove: (id: number) => http.del<void>(`/api/memories/${id}`),
  clearAll: () => http.del<void>('/api/memories')
}

