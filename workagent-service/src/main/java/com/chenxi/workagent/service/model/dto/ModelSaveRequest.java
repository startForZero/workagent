package com.chenxi.workagent.service.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 添加/更新模型请求。apiKey 仅在创建或需要更换时传入。
 * @author 辰夕
 */
public record ModelSaveRequest(
        @NotBlank @Size(max = 32) String provider,
        @NotBlank @Size(max = 128) String model,
        @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 256) String apiKey) {
}
