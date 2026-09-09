package com.chenxi.workagent.service.model.dto;

/**
 * 服务商预置模板（前端添加模型时选择）。
 * @author 辰夕
 */
public record ProviderTemplate(
        String provider,
        String label,
        String defaultBaseUrl,
        String recommendedModel) {
}
