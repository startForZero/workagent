package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话表 wa_session。
 * @author 辰夕
 */
@Data
@TableName("wa_session")
public class SessionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外会话标识（UUID），同时作为 RuntimeContext.sessionId */
    private String sessionId;

    private Long userId;

    private String title;

    /** 状态：1 正常 0 归档 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
