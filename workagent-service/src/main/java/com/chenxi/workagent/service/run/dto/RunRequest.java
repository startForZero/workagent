package com.chenxi.workagent.service.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发起运行请求。modelKey 由前端按 run 动态传入（后端只认 wa_user_model 已存配置）。
 * @author 辰夕
 */
public record RunRequest(
        @NotBlank String sessionId,
        @NotBlank @Size(max = 8000) String message,
        List<String> fileIds,
        @Size(max = 160) String modelKey) {
}
