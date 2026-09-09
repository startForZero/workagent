/**
 * @author 辰夕
 */
import { defineStore } from 'pinia'
import { authApi, userApi, type Profile } from '../api'
import { setToken, clearToken } from '../router'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    profile: null as Profile | null
  }),
  actions: {
    async login(email: string, password: string) {
      const resp = await authApi.login(email, password)
      setToken(resp.token)
      await this.fetchProfile()
    },
    async register(nickname: string, email: string, password: string) {
      const resp = await authApi.register(nickname, email, password)
      setToken(resp.token)
      await this.fetchProfile()
    },
    async fetchProfile() {
      this.profile = await userApi.me()
    },
    logout() {
      clearToken()
      this.profile = null
      location.href = '/login'
    }
  }
})
