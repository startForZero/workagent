package com.chenxi.workagent.service.user.dto;

/**
 * 个人资料响应。
 * @author 辰夕
 */
public record ProfileResponse(Long userId, String email, String nickname, String avatarUrl, String bio,
                              String role) {
}
