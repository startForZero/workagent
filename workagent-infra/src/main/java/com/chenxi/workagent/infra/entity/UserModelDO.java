package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户模型配置表 wa_user_model（BYOK）。
 * @author 辰夕
 */
@Data
@TableName("wa_user_model")
public class UserModelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 服务商：dashscope / deepseek / kimi / minimax / openai / custom */
    private String provider;

    /** 模型名，如 deepseek-chat */
    private String model;

    private String baseUrl;

    /** AES-GCM 加密后的 apiKey，禁止入日志、不回显 */
    @JsonIgnore
    private String apiKeyEnc;

    /** 是否启用：1 启用 0 停用 */
    private Integer enabled;

    private LocalDateTime createdAt;
}
