package com.chenxi.workagent.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 沙箱产物（wa_artifact）：agent 经 deliver_artifact 归档到 MinIO 的产出文件。
 * @author 辰夕
 */
@Data
@TableName("wa_artifact")
public class ArtifactDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long sessionId;

    private String runId;

    /** 展示文件名（deliver 请求中的 fileName） */
    private String fileName;

    /** MinIO 对象路径：artifacts/{userId}/{sessionId}/{fileName} */
    private String ossPath;

    private Long size;

    private String contentType;

    private LocalDateTime createdAt;
}
