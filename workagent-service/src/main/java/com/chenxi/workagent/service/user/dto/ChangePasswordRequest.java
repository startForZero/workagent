package com.chenxi.workagent.service.user.dto;

import com.chenxi.workagent.service.auth.dto.RegisterRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改密码请求（需校验当前密码）。
 * @author 辰夕
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Pattern(regexp = RegisterRequest.PASSWORD_RULE, message = "密码须 ≥8 位且含字母与数字")
        String newPassword) {
}
