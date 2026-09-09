/**
 * @author 辰夕
 */
import MarkdownIt from 'markdown-it'

/**
 * Markdown 渲染器（用于助手消息正文）。
 * html:false —— 禁止内嵌原始 HTML，模型输出中的标签一律转义，防注入。
 */
export const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false
})

// 链接一律新窗口打开（在 token 流上处理，避免覆写渲染规则）
md.core.ruler.push('link-target-blank', (state) => {
  for (const token of state.tokens) {
    if (token.type !== 'inline' || !token.children) continue
    for (const child of token.children) {
      if (child.type === 'link_open') {
        child.attrSet('target', '_blank')
        child.attrSet('rel', 'noopener noreferrer')
      }
    }
  }
  return false
})

export function renderMarkdown(text: string): string {
  return md.render(text)
}
