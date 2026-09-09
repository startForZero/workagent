package com.chenxi.workagent.service.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改个人资料请求（字段为空表示不修改）。
 * @author 辰夕
 */
public record UpdateProfileRequest(
        @Size(max = 32) String nickname,
        @Size(max = 512) String avatarUrl,
        @Size(max = 256) String bio) {
}
