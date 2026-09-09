package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户表 wa_user。
 * @author 辰夕
 */
@Data
@TableName("wa_user")
public class UserDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String nickname;

    private String avatarUrl;

    private String bio;

    private String passwordHash;

    /** 角色：USER / ADMIN（见 SecurityConstants 的 ROLE_* 前缀约定） */
    private String role;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;
}
