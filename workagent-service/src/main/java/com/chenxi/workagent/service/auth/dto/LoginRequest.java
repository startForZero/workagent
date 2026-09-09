package com.chenxi.workagent.service.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 * @author 辰夕
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
