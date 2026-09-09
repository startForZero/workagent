package com.chenxi.workagent.service.session.dto;

import java.time.LocalDateTime;

/**
 * 历史消息响应。content 为 JSON 字符串（结构见 MessageDO 注释），由前端解析渲染。
 * @author 辰夕
 */
public record MessageResponse(String role, String content, LocalDateTime createdAt) {
}
