package com.chenxi.workagent.infra.common.constant;

/**
 * MinIO 存储路径常量。
 * @author 辰夕
 */
public final class MinioConstants {

    private MinioConstants() {
    }

    /** 上传附件路径前缀：uploads/{userId}/{fileId}/{filename} */
    public static final String PREFIX_UPLOADS = "uploads/";

    /** 技能公共分区前缀（M3） */
    public static final String PREFIX_SKILL_PUBLIC = "public/";

    /** 技能私人分区前缀（M3）：users/{userId}/ */
    public static final String PREFIX_SKILL_USER = "users/";

    public static String uploadObjectName(Long userId, String fileId, String filename) {
        return PREFIX_UPLOADS + userId + "/" + fileId + "/" + filename;
    }
}
