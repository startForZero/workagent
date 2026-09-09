package com.chenxi.workagent.service.auth;

import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验（HS256）。
 * @author 辰夕
 */
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expireMillis;

    public JwtService(WorkagentProperties properties) {
        byte[] keyBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // HS256 要求密钥 ≥ 256 bit，不足时拒绝启动而非降级
            throw new IllegalStateException("workagent.jwt.secret 长度须 ≥ 32 字节");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = Duration.ofDays(properties.getJwt().getExpireDays()).toMillis();
    }

    /**
     * 签发 token，subject 为 userId。
     */
    public String issue(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 校验并解析 userId；无效/过期抛 UNAUTHORIZED。
     */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | NumberFormatException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
