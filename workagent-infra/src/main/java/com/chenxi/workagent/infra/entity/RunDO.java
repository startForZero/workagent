package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 运行表 wa_run（一次问答 = 一个 run）。
 * @author 辰夕
 */
@Data
@TableName("wa_run")
public class RunDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外运行标识（UUID） */
    private String runId;

    private Long sessionId;

    /** 见 RunStatus 枚举 */
    private String status;

    /** 本次运行使用的 modelKey（provider:model） */
    private String modelKey;

    private Long tokensIn;

    private Long tokensOut;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;
}
