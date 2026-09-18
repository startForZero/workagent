/**
 * @author 辰夕
 */
/** 原型风格确认弹窗（居中卡片 + 遮罩，替代 window.confirm；Promise 返回用户选择） */

export interface ConfirmOptions {
  /** 标题，默认「操作确认」 */
  title?: string
  /** 确认按钮文案，默认「确定」 */
  confirmText?: string
  /** 危险操作（确认按钮红色），默认 true——confirm 场景多为删除 */
  danger?: boolean
}

let active: { close: (ok: boolean) => void } | null = null

export function confirmDialog(message: string, opts: ConfirmOptions = {}): Promise<boolean> {
  // 同屏只保留一个弹窗：重复触发视为取消前一个
  active?.close(false)

  return new Promise((resolve) => {
    const mask = document.createElement('div')
    mask.className = 'cf-mask'
    mask.innerHTML = `
      <div class="cf-box" role="dialog" aria-modal="true">
        <div class="cf-title"></div>
        <div class="cf-msg"></div>
        <div class="cf-ops">
          <button type="button" class="btn btn-ghost cf-cancel">取消</button>
          <button type="button" class="btn cf-ok"></button>
        </div>
      </div>`
    const titleEl = mask.querySelector<HTMLDivElement>('.cf-title')!
    const msgEl = mask.querySelector<HTMLDivElement>('.cf-msg')!
    const okBtn = mask.querySelector<HTMLButtonElement>('.cf-ok')!
    const cancelBtn = mask.querySelector<HTMLButtonElement>('.cf-cancel')!
    titleEl.textContent = opts.title ?? '操作确认'
    msgEl.textContent = message
    okBtn.textContent = opts.confirmText ?? '确定'
    okBtn.classList.add(opts.danger === false ? 'btn-primary' : 'cf-danger')

    const close = (ok: boolean) => {
      window.removeEventListener('keydown', onKey, true)
      mask.remove()
      active = null
      resolve(ok)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close(false)
      else if (e.key === 'Enter') close(true)
    }
    okBtn.addEventListener('click', () => close(true))
    cancelBtn.addEventListener('click', () => close(false))
    mask.addEventListener('click', (e) => {
      if (e.target === mask) close(false)
    })
    window.addEventListener('keydown', onKey, true)

    active = { close }
    document.body.appendChild(mask)
    okBtn.focus()
  })
}
