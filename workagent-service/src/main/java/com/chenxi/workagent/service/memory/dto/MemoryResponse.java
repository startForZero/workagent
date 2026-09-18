package com.chenxi.workagent.service.memory.dto;

import java.time.LocalDateTime;

/**
 * 记忆中心列表项。
 * @author 辰夕
 */
public record MemoryResponse(
        Long id,
        String content,
        /** 来源会话（memory_save 拦截回填；框架归纳条目/历史条目为 null） */
        Long sourceSessionId,
        /** 来源会话标题；会话已删除或无来源时为 null，前端显示「未知」 */
        String sourceSessionTitle,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
