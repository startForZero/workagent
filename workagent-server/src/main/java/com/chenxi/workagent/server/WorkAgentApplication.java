package com.chenxi.workagent.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 辰夕 WorkAgent 启动类。
 * @author 辰夕
 */
@SpringBootApplication(scanBasePackages = "com.chenxi.workagent")
@MapperScan("com.chenxi.workagent.infra.mapper")
public class WorkAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkAgentApplication.class, args);
    }
}
