package com.chenxi.workagent.service.skill.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能市场列表项。
 * @author 辰夕
 */
public record SkillSummaryResponse(
        Long id,
        String skillKey,
        String scope,
        String description,
        List<String> tags,
        int fileCount,
        long totalSize,
        /** 是否当前登录用户上传的技能 */
        boolean mine,
        LocalDateTime updatedAt) {
}
