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

/** 历史消息（content 为 JSON 字符串，结构见后端 MessageDO 注释） */
export interface HistoryMessage {
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

export interface UploadedFile {
  fileId: string
  filename: string
  size: number
  contentType?: string
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
  messages: (sessionId: string) => http.get<HistoryMessage[]>(`/api/sessions/${sessionId}/messages`)
}

export const fileApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<UploadedFile>('/api/files', form)
  }
}
