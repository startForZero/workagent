package com.chenxi.workagent.service.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（邮箱注册，无验证码）。
 * @author 辰夕
 */
public record RegisterRequest(
        @NotBlank @Size(max = 32) String nickname,
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = PASSWORD_RULE, message = "密码须 ≥8 位且含字母与数字") String password) {

    /** 密码规则：≥8 位，含字母与数字 */
    public static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$";
}
