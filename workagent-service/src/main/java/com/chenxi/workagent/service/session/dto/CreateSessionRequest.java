package com.chenxi.workagent.service.session.dto;

import jakarta.validation.constraints.Size;

/**
 * 新建会话请求（标题可空，首条消息自动生成）。
 * @author 辰夕
 */
public record CreateSessionRequest(@Size(max = 64) String title) {
}
