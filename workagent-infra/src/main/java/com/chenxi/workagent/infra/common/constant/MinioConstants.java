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

    /** 技能 zip 对象名（每技能固定一个对象，覆盖即更新，ETag 作版本指纹） */
    public static final String SKILL_ZIP_NAME = "skill.zip";

    public static String uploadObjectName(Long userId, String fileId, String filename) {
        return PREFIX_UPLOADS + userId + "/" + fileId + "/" + filename;
    }

    /**
     * 技能包对象名：公共技能 public/{skillKey}/skill.zip；用户技能 users/{userId}/{skillKey}/skill.zip。
     *
     * @param publicSkill 是否公共技能
     */
    public static String skillObjectName(boolean publicSkill, Long userId, String skillKey) {
        if (publicSkill) {
            return PREFIX_SKILL_PUBLIC + skillKey + "/" + SKILL_ZIP_NAME;
        }
        return PREFIX_SKILL_USER + userId + "/" + skillKey + "/" + SKILL_ZIP_NAME;
    }
}
