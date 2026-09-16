package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 技能市场条目（wa_skill）：包体为 MinIO 单 zip 对象，本表存元数据；
 * 无版本设计——同 scope 下重名导入即覆盖，etag 为当前包指纹（本地缓存判失效依据）。
 * @author 辰夕
 */
@Data
@TableName("wa_skill")
public class SkillDO {

    /** 可见范围：公共（管理员维护，全员可见） */
    public static final String SCOPE_PUBLIC = "PUBLIC";
    /** 可见范围：用户私有（仅属主可见） */
    public static final String SCOPE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 技能标识 = SKILL.md frontmatter 的 name（slug：[a-z0-9-]），同一 scope 内唯一 */
    private String skillKey;

    /** PUBLIC / USER */
    private String scope;

    /** USER 技能属主；PUBLIC 为 NULL */
    private Long ownerUserId;

    private String description;

    /** 逗号分隔标签 */
    private String tags;

    /** 包内文件路径列表（JSON 数组） */
    private String filesJson;

    /** zip 字节数 */
    private Long totalSize;

    /** MinIO 对象 ETag */
    private String etag;

    /** MinIO 对象路径（见 MinioConstants.skillObjectName） */
    private String ossPath;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
