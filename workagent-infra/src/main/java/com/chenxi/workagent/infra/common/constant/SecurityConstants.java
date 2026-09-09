package com.chenxi.workagent.infra.common.constant;

/**
 * 安全相关常量。
 * @author 辰夕
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** 请求头中的 JWT 携带方式 */
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    /** 角色 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";

    /** 注入 request attribute 的当前用户 ID 键 */
    public static final String ATTR_USER_ID = "currentUserId";
}
