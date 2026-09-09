package com.chenxi.workagent.api.security;

import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.infra.common.enums.UserRole;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.entity.UserDO;
import com.chenxi.workagent.infra.mapper.UserMapper;
import com.chenxi.workagent.service.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：解析 Bearer token，userId 注入 request attribute 与 SecurityContext。
 * 角色以数据库为准（管理员变更即时生效）；RuntimeContext.userId 一律取自此处，不信任前端传值。
 * @author 辰夕
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Spring Security 角色前缀 */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(SecurityConstants.HEADER_AUTHORIZATION);
        if (header != null && header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            try {
                Long userId = jwtService.parseUserId(header.substring(SecurityConstants.TOKEN_PREFIX.length()));
                request.setAttribute(SecurityConstants.ATTR_USER_ID, userId);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null,
                                List.of(new SimpleGrantedAuthority(ROLE_PREFIX + resolveRole(userId))));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BizException e) {
                // token 无效：不在这里阻断，交由 SecurityConfig 的入口点统一返回 401
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveRole(Long userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null || !StringUtils.hasText(user.getRole())) {
            return UserRole.USER.name();
        }
        return user.getRole();
    }
}
