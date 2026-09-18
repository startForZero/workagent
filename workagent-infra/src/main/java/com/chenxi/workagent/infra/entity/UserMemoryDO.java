package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户长期记忆镜像（wa_user_memory）：MEMORY.md 文件为真相源（框架维护），
 * 本表仅存 UI 镜像与来源会话元数据，靠 read-repair 对账与文件保持一致。
 * @author 辰夕
 */
@Data
@TableName("wa_user_memory")
public class UserMemoryDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 单条记忆全文（bullet 去前缀后） */
    private String content;

    /** SHA-256(content)，对账与幂等键（uk_user_hash） */
    private String contentHash;

    /** 来源会话 wa_session.id（memory_save 拦截回填；归纳条目为 NULL；会话删除后悬空） */
    private Long sourceSessionId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
