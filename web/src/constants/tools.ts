/**
 * @author 辰夕
 */
/**
 * 工具展示名（key 为框架工具名，未命中回退原名）。
 * 参考 WorkBuddy：行内步骤行显示「运行命令: xxx」这类友好文案。
 */
const TOOL_LABELS: Record<string, string> = {
  execute: '运行命令',
  shell_execute: '运行命令',
  memory_save: '保存记忆',
  memory_search: '搜索记忆',
  memory_get: '读取记忆',
  session_search: '搜索会话记忆'
}

export function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}
