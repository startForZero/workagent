package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.model.ModelService;
import com.chenxi.workagent.service.model.ProviderTemplates;
import com.chenxi.workagent.service.model.dto.ModelSaveRequest;
import com.chenxi.workagent.service.model.dto.ProviderTemplate;
import com.chenxi.workagent.service.model.dto.UserModelResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户模型管理接口（BYOK）。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @GetMapping("/providers")
    public ApiResult<List<ProviderTemplate>> providers() {
        return ApiResult.ok(ProviderTemplates.ALL);
    }

    @GetMapping
    public ApiResult<List<UserModelResponse>> list(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId) {
        return ApiResult.ok(modelService.list(userId));
    }

    @PostMapping
    public ApiResult<UserModelResponse> create(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                               @Valid @RequestBody ModelSaveRequest request) {
        return ApiResult.ok(modelService.create(userId, request));
    }

    /** 连通性测试（添加前先试，不落库） */
    @PostMapping("/test")
    public ApiResult<Void> test(@Valid @RequestBody ModelSaveRequest request) {
        modelService.testConnectivity(request.provider(), request.model(), request.baseUrl(), request.apiKey());
        return ApiResult.ok();
    }

    @PutMapping("/{id}")
    public ApiResult<Void> setEnabled(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                      @PathVariable("id") Long modelId,
                                      @RequestParam("enabled") boolean enabled) {
        modelService.setEnabled(userId, modelId, enabled);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                  @PathVariable("id") Long modelId) {
        modelService.delete(userId, modelId);
        return ApiResult.ok();
    }
}
