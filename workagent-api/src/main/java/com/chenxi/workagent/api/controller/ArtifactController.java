package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.artifact.ArtifactService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产物接口：下载（预签名 URL）。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;

    @GetMapping("/{id}/download")
    public ApiResult<Map<String, String>> download(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                                   @PathVariable("id") Long artifactId) {
        return ApiResult.ok(Map.of("url", artifactService.downloadUrl(userId, artifactId)));
    }
}
