package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上传文件表 wa_file。
 * @author 辰夕
 */
@Data
@TableName("wa_file")
public class FileDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外文件标识（UUID） */
    private String fileId;

    private Long userId;

    private Long sessionId;

    private String filename;

    private String contentType;

    private Long size;

    private String ossPath;

    private LocalDateTime createdAt;
}
