package com.chenxi.workagent.service.auth.dto;

/**
 * 登录/注册成功响应。
 * @author 辰夕
 */
public record AuthResponse(String token, Long userId, String nickname, String avatarUrl, String role) {
}
