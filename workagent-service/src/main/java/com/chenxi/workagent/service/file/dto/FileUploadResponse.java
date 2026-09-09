package com.chenxi.workagent.service.file.dto;

/**
 * 文件上传响应。
 * @author 辰夕
 */
public record FileUploadResponse(String fileId, String filename, Long size, String contentType) {
}
