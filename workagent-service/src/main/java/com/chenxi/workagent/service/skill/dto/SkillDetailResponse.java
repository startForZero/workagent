package com.chenxi.workagent.service.skill.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能详情（含包内文件路径列表，供文件树渲染）。
 * @author 辰夕
 */
public record SkillDetailResponse(
        Long id,
        String skillKey,
        String scope,
        String description,
        List<String> tags,
        List<String> files,
        long totalSize,
        boolean mine,
        LocalDateTime updatedAt) {
}
