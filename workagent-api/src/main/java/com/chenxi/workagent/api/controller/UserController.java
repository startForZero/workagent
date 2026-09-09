package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.user.UserService;
import com.chenxi.workagent.service.user.dto.ChangePasswordRequest;
import com.chenxi.workagent.service.user.dto.ProfileResponse;
import com.chenxi.workagent.service.user.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人资料接口。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResult<ProfileResponse> me(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId) {
        return ApiResult.ok(userService.profile(userId));
    }

    @PutMapping("/me")
    public ApiResult<ProfileResponse> update(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                             @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResult.ok(userService.updateProfile(userId, request));
    }

    @PutMapping("/me/password")
    public ApiResult<Void> changePassword(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                          @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        return ApiResult.ok();
    }
}
