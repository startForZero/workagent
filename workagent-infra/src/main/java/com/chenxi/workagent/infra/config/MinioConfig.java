package com.chenxi.workagent.infra.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端装配。
 * @author 辰夕
 */
@Configuration
@EnableConfigurationProperties(WorkagentProperties.class)
public class MinioConfig {

    @Bean
    public MinioClient minioClient(WorkagentProperties properties) {
        WorkagentProperties.Minio minio = properties.getMinio();
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }
}
