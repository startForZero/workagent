-- 辰夕 WorkAgent M1 库表（MySQL 8）
-- 显式指定连接字符集，防止非 utf8mb4 客户端执行时中文默认值/注释乱码
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS workagent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE workagent;

-- 用户
CREATE TABLE IF NOT EXISTS wa_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(128) NOT NULL,
    nickname      VARCHAR(32)  NOT NULL,
    avatar_url    VARCHAR(512) NULL,
    bio           VARCHAR(256) NULL,
    password_hash VARCHAR(128) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    created_at    DATETIME     NOT NULL,
    UNIQUE KEY uk_email (email)
) ENGINE = InnoDB;

-- 用户模型配置（BYOK）
CREATE TABLE IF NOT EXISTS wa_user_model (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    provider   VARCHAR(32)  NOT NULL COMMENT 'dashscope/deepseek/kimi/minimax/openai/custom',
    model      VARCHAR(128) NOT NULL,
    base_url   VARCHAR(512) NULL,
    api_key_enc VARCHAR(1024) NOT NULL COMMENT 'AES-GCM 加密',
    enabled    TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL,
    KEY idx_user (user_id)
) ENGINE = InnoDB;

-- 上传文件
CREATE TABLE IF NOT EXISTS wa_file (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id      VARCHAR(32)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    session_id   BIGINT       NULL,
    filename     VARCHAR(256) NOT NULL,
    content_type VARCHAR(128) NULL,
    size         BIGINT       NOT NULL,
    oss_path     VARCHAR(512) NOT NULL,
    created_at   DATETIME     NOT NULL,
    UNIQUE KEY uk_file_id (file_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB;

-- 会话
CREATE TABLE IF NOT EXISTS wa_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(32)  NOT NULL,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(64)  NOT NULL DEFAULT '新对话',
    status     TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB;

-- 运行（一次问答 = 一个 run）
CREATE TABLE IF NOT EXISTS wa_run (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id      VARCHAR(32) NOT NULL,
    session_id  BIGINT      NOT NULL COMMENT 'wa_session.id',
    status      VARCHAR(32) NOT NULL COMMENT 'RUNNING/WAITING_INPUT/WAITING_CONFIRM/DONE/ERROR/CANCELLED/TIMEOUT',
    model_key   VARCHAR(160) NULL,
    tokens_in   BIGINT NULL,
    tokens_out  BIGINT NULL,
    started_at  DATETIME    NOT NULL,
    ended_at    DATETIME    NULL,
    UNIQUE KEY uk_run_id (run_id),
    KEY idx_session (session_id)
) ENGINE = InnoDB;

-- 会话消息（一次问答产生 user/assistant 两条，content 为 JSON）
CREATE TABLE IF NOT EXISTS wa_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT      NOT NULL COMMENT 'wa_session.id',
    run_id     VARCHAR(32) NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    content    JSON        NOT NULL,
    created_at DATETIME    NOT NULL,
    KEY idx_session (session_id)
) ENGINE = InnoDB;
