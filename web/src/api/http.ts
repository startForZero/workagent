/**
 * @author 辰夕
 */
import { getToken, clearToken } from '../router'

/** 统一 REST 响应包络 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  constructor(public code: number, message: string) {
    super(message)
  }
}

const UNAUTHORIZED = 1002

async function request<T>(method: string, url: string, body?: unknown, form?: FormData): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const resp = await fetch(url, {
    method,
    headers,
    body: form ?? (body !== undefined ? JSON.stringify(body) : undefined)
  })

  if (resp.status === 401) {
    clearToken()
    location.href = '/login'
    throw new ApiError(UNAUTHORIZED, '登录已过期')
  }
  const result: ApiResult<T> = await resp.json()
  if (result.code !== 0) {
    throw new ApiError(result.code, result.message)
  }
  return result.data
}

export const http = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  put: <T>(url: string, body?: unknown) => request<T>('PUT', url, body),
  del: <T>(url: string) => request<T>('DELETE', url),
  upload: <T>(url: string, form: FormData) => request<T>('POST', url, undefined, form)
}
