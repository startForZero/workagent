/**
 * 技能图标与底色：后端不存图标，按 skillKey 哈希从固定调色板取（同一技能稳定同色）。
 * @author 辰夕
 */

/** 调色板（取自原型技能的 pastel 底色风格） */
const PALETTE = ['#e0e7ff', '#fce7f3', '#dcfce7', '#fef3c7', '#e0f2fe', '#f3e8ff', '#fee2e2', '#ccfbf1']
const ICONS = ['🧩', '📊', '📝', '🎨', '🔍', '🤖', '📈', '🛠️']

export function skillIcon(skillKey: string): string {
  return ICONS[hash(skillKey) % ICONS.length]
}

export function skillBg(skillKey: string): string {
  return PALETTE[hash(skillKey) % PALETTE.length]
}

function hash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0
  }
  return h
}
