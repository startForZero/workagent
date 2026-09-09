package com.chenxi.workagent.service.session.dto;

import java.time.LocalDateTime;

/**
 * 会话响应。
 * @author 辰夕
 */
public record SessionResponse(String sessionId, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
