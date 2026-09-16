package com.chenxi.workagent.service.artifact.dto;

import java.time.LocalDateTime;

/**
 * 产物列表项（不暴露内部 OSS 路径，下载走 /api/artifacts/{id}/download）。
 *
 * @author 辰夕
 */
public record ArtifactResponse(Long id, String fileName, Long size, String contentType,
                               String runId, LocalDateTime createdAt) {
}
