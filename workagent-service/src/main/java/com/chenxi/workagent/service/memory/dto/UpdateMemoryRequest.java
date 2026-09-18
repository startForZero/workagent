package com.chenxi.workagent.service.memory.dto;

/**
 * 编辑记忆请求（校验在 MemoryService 手动做，与 SkillService 风格一致）。
 * @author 辰夕
 */
public record UpdateMemoryRequest(String content) {
}
