package com.chenxi.workagent.service.model.dto;

import java.time.LocalDateTime;

/**
 * 用户模型响应（apiKey 不回显）。
 * @author 辰夕
 */
public record UserModelResponse(
        Long id,
        String provider,
        String model,
        String modelKey,
        String baseUrl,
        Boolean enabled,
        LocalDateTime createdAt) {
}
