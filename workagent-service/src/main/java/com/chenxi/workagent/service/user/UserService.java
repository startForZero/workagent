package com.chenxi.workagent.service.user;

import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.entity.UserDO;
import com.chenxi.workagent.infra.mapper.UserMapper;
import com.chenxi.workagent.service.user.dto.ChangePasswordRequest;
import com.chenxi.workagent.service.user.dto.ProfileResponse;
import com.chenxi.workagent.service.user.dto.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户资料服务。
 * @author 辰夕
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public ProfileResponse profile(Long userId) {
        UserDO user = requireUser(userId);
        return new ProfileResponse(user.getId(), user.getEmail(), user.getNickname(),
                user.getAvatarUrl(), user.getBio(), user.getRole());
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        UserDO user = requireUser(userId);
        if (StringUtils.hasText(request.nickname())) {
            user.setNickname(request.nickname());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        userMapper.updateById(user);
        return profile(userId);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        UserDO user = requireUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.PASSWORD_WRONG);
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userMapper.updateById(user);
    }

    private UserDO requireUser(Long userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
