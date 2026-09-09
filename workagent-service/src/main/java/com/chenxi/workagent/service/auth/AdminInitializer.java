package com.chenxi.workagent.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.enums.UserRole;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.UserDO;
import com.chenxi.workagent.infra.mapper.UserMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 内置管理员种子账号：启动时按 workagent.admin.email 查不到才创建。
 * 已存在则跳过（管理员改密/改名不被覆盖）。
 * @author 辰夕
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    /** 用户状态：正常 */
    private static final int STATUS_ACTIVE = 1;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final WorkagentProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        WorkagentProperties.Admin admin = properties.getAdmin();
        if (!StringUtils.hasText(admin.getEmail()) || !StringUtils.hasText(admin.getPassword())) {
            log.info("未配置 workagent.admin.email/password，跳过内置管理员创建");
            return;
        }
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getEmail, admin.getEmail()));
        if (count != null && count > 0) {
            return;
        }
        UserDO user = new UserDO();
        user.setEmail(admin.getEmail());
        user.setNickname(admin.getNickname());
        user.setPasswordHash(passwordEncoder.encode(admin.getPassword()));
        user.setRole(UserRole.ADMIN.name());
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
        log.info("内置管理员已创建: {}", admin.getEmail());
    }
}
