/**
 * @author 辰夕
 */
import { getToken } from '../router'

/** SSE 统一事件包络（与后端 SseEnvelope 对应） */
export interface SseEnvelope<T = unknown> {
  event: string
  runId: string
  seq: number
  data: T
}

/**
 * 终态事件（与后端 SseEventType 契约一致）：收到即视为本次 run 结束。
 * 不能只等 HTTP 连接关闭——经 dev 代理/反向代理时连接可能被保活挂住，
 * 导致流永远不「读完」、界面卡在生成中状态。
 */
const TERMINAL_EVENTS: ReadonlySet<string> = new Set(['run.end', 'run.error'])

/**
 * 发起 run 的 SSE 流（POST + fetch 流式读取；EventSource 不支持 POST/自定义头）。
 * 收到终态事件后立即返回并释放底层连接。
 */
export async function streamRun(
  body: { sessionId: string; message: string; fileIds?: string[]; modelKey?: string },
  onEvent: (envelope: SseEnvelope) => void,
  signal?: AbortSignal
): Promise<void> {
  const resp = await fetch('/api/runs', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify(body),
    signal
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`请求失败：HTTP ${resp.status}`)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 帧以空行分隔
      const frames = buffer.split('\n\n')
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        const dataLines = frame
          .split('\n')
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5))
        if (dataLines.length === 0) continue
        let envelope: SseEnvelope
        try {
          envelope = JSON.parse(dataLines.join('\n')) as SseEnvelope
        } catch {
          continue // 忽略心跳/注释帧
        }
        onEvent(envelope)
        if (TERMINAL_EVENTS.has(envelope.event)) {
          await reader.cancel().catch(() => {})
          return
        }
      }
    }
  } finally {
    reader.releaseLock()
  }
}
