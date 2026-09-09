/**
 * @author 辰夕
 */
import { defineStore } from 'pinia'
import { sessionApi, type SessionItem } from '../api'

/**
 * 会话状态：侧栏（AppLayout）与对话页（ChatView）共享。
 * currentSessionId 变化时由 ChatView 负责清空消息区。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [] as SessionItem[],
    currentSessionId: '' as string
  }),
  actions: {
    /** 首次进入应用：加载会话，无会话则自动建一个 */
    async init() {
      this.sessions = await sessionApi.list()
      if (this.sessions.length === 0) {
        const s = await sessionApi.create()
        this.sessions = [s]
        this.currentSessionId = s.sessionId
      } else if (!this.sessions.some((s) => s.sessionId === this.currentSessionId)) {
        this.currentSessionId = this.sessions[0].sessionId
      }
    },
    /** 静默刷新列表（run 结束后标题可能由后端自动生成） */
    async refresh() {
      this.sessions = await sessionApi.list()
    },
    async newSession() {
      const s = await sessionApi.create()
      this.sessions.unshift(s)
      this.currentSessionId = s.sessionId
    },
    select(sessionId: string) {
      this.currentSessionId = sessionId
    }
  }
})
