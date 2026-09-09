package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.file.FileService;
import com.chenxi.workagent.service.file.dto.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话附件上传接口。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping
    public ApiResult<FileUploadResponse> upload(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                                @RequestParam("file") MultipartFile file) {
        return ApiResult.ok(fileService.upload(userId, file));
    }
}
