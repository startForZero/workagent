package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话消息表 wa_message。一次问答产生 user / assistant 两条；
 * content 为 JSON：user 为 {text, files:[{name,size}]}，
 * assistant 为 {steps:[{kind,text|name,args,result,status}], text, error?}。
 * @author 辰夕
 */
@Data
@TableName("wa_message")
public class MessageDO {

    /** 角色：用户 */
    public static final String ROLE_USER = "user";
    /** 角色：助手 */
    public static final String ROLE_ASSISTANT = "assistant";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** wa_session.id */
    private Long sessionId;

    /** 所属 run（wa_run.run_id） */
    private String runId;

    /** user / assistant */
    private String role;

    /** 消息内容（JSON） */
    private String content;

    private LocalDateTime createdAt;
}
