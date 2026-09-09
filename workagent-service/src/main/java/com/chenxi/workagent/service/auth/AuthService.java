package com.chenxi.workagent.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.enums.UserRole;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.entity.UserDO;
import com.chenxi.workagent.infra.mapper.UserMapper;
import com.chenxi.workagent.service.auth.dto.AuthResponse;
import com.chenxi.workagent.service.auth.dto.LoginRequest;
import com.chenxi.workagent.service.auth.dto.RegisterRequest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号服务：注册（无邮箱验证码）与登录。
 * @author 辰夕
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用户状态：正常 */
    private static final int STATUS_ACTIVE = 1;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getEmail, request.email()));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        UserDO user = new UserDO();
        user.setEmail(request.email());
        user.setNickname(request.nickname());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER.name());
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getEmail, request.email()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.EMAIL_OR_PASSWORD_WRONG);
        }
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(UserDO user) {
        return new AuthResponse(jwtService.issue(user.getId()),
                user.getId(), user.getNickname(), user.getAvatarUrl(), user.getRole());
    }
}
