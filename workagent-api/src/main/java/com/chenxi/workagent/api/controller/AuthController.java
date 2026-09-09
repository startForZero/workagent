package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.service.auth.AuthService;
import com.chenxi.workagent.service.auth.dto.AuthResponse;
import com.chenxi.workagent.service.auth.dto.LoginRequest;
import com.chenxi.workagent.service.auth.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（注册/登录，无需认证）。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResult<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResult<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(authService.login(request));
    }
}
