/**
 * @author 辰夕
 */
/** 原型风格轻提示（顶部居中深色胶囊，对应 docs/prototype/index.html 的 toast） */

let el: HTMLDivElement | null = null
let timer: ReturnType<typeof setTimeout> | undefined

const SHOW_MS = 2200

export function toast(text: string) {
  if (!el) {
    el = document.createElement('div')
    el.className = 'toast'
    document.body.appendChild(el)
  }
  el.textContent = text
  el.classList.add('show')
  clearTimeout(timer)
  timer = setTimeout(() => el?.classList.remove('show'), SHOW_MS)
}
