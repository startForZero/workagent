package com.chenxi.workagent.agent.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agentscope 会话状态存储装配：RedisAgentStateStore（AgentState JSON 外置，支持多实例 resume）。
 * 与业务 Redis 共用实例，但使用独立的 Lettuce 客户端（框架适配器要求）。
 * @author 辰夕
 */
@Configuration
public class AgentStateConfig {

    /** 框架状态 key 前缀 */
    private static final String STATE_KEY_PREFIX = "wa:agent:";

    @Bean(destroyMethod = "shutdown")
    public RedisClient agentscopeRedisClient(RedisProperties redisProperties) {
        RedisURI uri = RedisURI.builder()
                .withHost(redisProperties.getHost())
                .withPort(redisProperties.getPort())
                .withDatabase(redisProperties.getDatabase())
                .build();
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            uri.setPassword(redisProperties.getPassword().toCharArray());
        }
        return RedisClient.create(uri);
    }

    @Bean
    public AgentStateStore agentStateStore(RedisClient agentscopeRedisClient) {
        return RedisAgentStateStore.builder()
                .keyPrefix(STATE_KEY_PREFIX)
                .lettuceClient(agentscopeRedisClient)
                .build();
    }
}
