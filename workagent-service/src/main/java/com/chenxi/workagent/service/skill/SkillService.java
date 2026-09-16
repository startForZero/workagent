package com.chenxi.workagent.service.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.constant.MinioConstants;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.enums.UserRole;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.SkillDO;
import com.chenxi.workagent.infra.entity.UserDO;
import com.chenxi.workagent.infra.mapper.SkillMapper;
import com.chenxi.workagent.infra.mapper.UserMapper;
import com.chenxi.workagent.infra.storage.MinioStorageService;
import com.chenxi.workagent.service.skill.dto.SkillDetailResponse;
import com.chenxi.workagent.service.skill.dto.SkillSummaryResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 技能市场服务：zip 导入/列表/详情/导出/删除。
 * 包体存 MinIO（单 zip 对象，覆盖即更新），元数据落 wa_skill；公共区仅管理员可维护。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    /** 技能 key 长度上限（不做字符集限制：用户自己的技能想叫啥叫啥，包括中文 / 含点 / 含空格等） */
    private static final int SKILL_KEY_MAX_LEN = 64;
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    private final SkillMapper skillMapper;
    private final UserMapper userMapper;
    private final MinioStorageService storageService;
    private final SkillCacheStore cacheStore;
    private final WorkagentSkillRepository skillRepository;
    private final WorkagentProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 导入技能 zip：校验 → 解析 SKILL.md frontmatter → 上传 MinIO → 落库（同 scope 重名即覆盖）。
     *
     * @param scope PUBLIC（仅管理员）/ USER
     */
    public SkillSummaryResponse importZip(Long userId, String scope, MultipartFile file) {
        boolean publicScope = SkillDO.SCOPE_PUBLIC.equals(scope);
        if (publicScope && !isAdmin(userId)) {
            throw new BizException(ErrorCode.SKILL_FORBIDDEN);
        }
        if (file.getSize() > properties.getSkill().getMaxSizeMb() * 1024 * 1024) {
            throw new BizException(ErrorCode.SKILL_TOO_LARGE);
        }
        byte[] zipBytes;
        try {
            zipBytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        // 解压到临时目录做校验，解析通过后直接安装为本地缓存
        Path staging;
        List<String> files;
        try {
            staging = Files.createTempDirectory("skill-import-");
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "技能包解析失败");
        }
        try {
            MarkdownSkillParser.ParsedMarkdown parsed;
            try {
                files = cacheStore.unzip(new ByteArrayInputStream(zipBytes), staging);
                if (files.isEmpty()) {
                    throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
                }
                // 直接压缩文件夹产出的 zip 会多包一层目录（SKILL.md 不在根）：上提一层归一化，
                // 并以归一化后的目录树重算文件列表（files_json / 文件数）
                if (cacheStore.flattenWrapperIfNeeded(staging)) {
                    files = cacheStore.listFiles(staging);
                }
                Path skillMd = staging.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
                }
                parsed = MarkdownSkillParser.parse(Files.readString(skillMd, StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                log.warn("技能包解压/读取失败: {}", e.getMessage());
                throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
            }
            if (!parsed.hasFrontmatter()) {
                throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
            }
            String name = stringMeta(parsed, "name");
            String description = stringMeta(parsed, "description");
            if (name == null || description == null || name.isBlank() || name.length() > SKILL_KEY_MAX_LEN) {
                throw new BizException(ErrorCode.SKILL_IMPORT_INVALID,
                        "技能 name 必填且不超过 " + SKILL_KEY_MAX_LEN + " 字符");
            }

            Long ownerId = publicScope ? null : userId;
            String ossPath = MinioConstants.skillObjectName(publicScope, userId, name);
            String etag = storageService.putReturningEtag(properties.getMinio().getBucketSkills(),
                    ossPath, new ByteArrayInputStream(zipBytes), zipBytes.length, ZIP_CONTENT_TYPE);

            LocalDateTime now = LocalDateTime.now();
            SkillDO skill = findByScopeAndKey(publicScope, ownerId, name);
            boolean isNew = skill == null;
            if (isNew) {
                skill = new SkillDO();
                skill.setSkillKey(name);
                skill.setScope(scope);
                skill.setOwnerUserId(ownerId);
                skill.setCreatedAt(now);
            }
            skill.setDescription(description);
            skill.setTags(tagsMeta(parsed));
            skill.setFilesJson(writeJson(files));
            skill.setTotalSize((long) zipBytes.length);
            skill.setEtag(etag);
            skill.setOssPath(ossPath);
            skill.setUpdatedAt(now);
            if (isNew) {
                skillMapper.insert(skill);
            } else {
                skillMapper.updateById(skill);
            }
            // 同 scope 重名=更新：以新包重建本地缓存
            cacheStore.install(skill, staging);
            staging = null; // 已 move 走，无需清理
            skillRepository.invalidateVisibleCache();
            log.info("技能导入: scope={}, skillKey={}, userId={}, files={}, etag={}",
                    scope, name, userId, files.size(), etag);
            return toSummary(skill, userId, files.size());
        } finally {
            if (staging != null) {
                try {
                    Files.walk(staging)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (Exception ignored) {
                                    // 尽力清理
                                }
                            });
                } catch (Exception ignored) {
                    // 尽力清理
                }
            }
        }
    }

    /** 技能市场列表：公共 + 我的；scope 过滤（PUBLIC/USER/空=全部），keyword 匹配标识/描述/标签。 */
    public List<SkillSummaryResponse> list(Long userId, String scope, String keyword) {
        return listVisible(userId).stream()
                .filter(s -> scope == null || scope.isBlank() || scope.equals(s.getScope()))
                .filter(s -> keyword == null || keyword.isBlank() || matchesKeyword(s, keyword))
                .map(s -> toSummary(s, userId, fileCount(s)))
                .toList();
    }

    /** 技能详情（可见性校验：公共或本人）。 */
    public SkillDetailResponse detail(Long userId, Long id) {
        SkillDO skill = requireVisible(userId, id);
        List<String> files = readJson(skill.getFilesJson());
        return new SkillDetailResponse(skill.getId(), skill.getSkillKey(), skill.getScope(),
                skill.getDescription(), splitTags(skill.getTags()), files, skill.getTotalSize(),
                isMine(skill, userId), skill.getUpdatedAt());
    }

    /** 读取包内文件内容（详情页预览；限文本、限大小）。 */
    public String fileContent(Long userId, Long id, String path) {
        SkillDO skill = requireVisible(userId, id);
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (!readJson(skill.getFilesJson()).contains(path)) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        Path file = cacheStore.ensureCached(skill).resolve(path).normalize();
        try {
            if (Files.size(file) > properties.getSkill().getPreviewMaxSizeKb() * 1024) {
                throw new BizException(ErrorCode.PARAM_INVALID, "文件过大，请导出后查看");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 二进制文件按不可预览处理
            throw new BizException(ErrorCode.PARAM_INVALID, "该文件不支持预览");
        }
    }

    /** 导出：返回 zip 预签名下载 URL。 */
    public String exportUrl(Long userId, Long id) {
        SkillDO skill = requireVisible(userId, id);
        return storageService.presignGet(properties.getMinio().getBucketSkills(), skill.getOssPath());
    }

    /** 删除：USER 技能仅属主，PUBLIC 技能仅管理员；连带清 MinIO 对象与本地缓存。 */
    public void delete(Long userId, Long id) {
        SkillDO skill = requireVisible(userId, id);
        if (SkillDO.SCOPE_PUBLIC.equals(skill.getScope())) {
            if (!isAdmin(userId)) {
                throw new BizException(ErrorCode.SKILL_FORBIDDEN);
            }
        } else if (!isMine(skill, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        storageService.remove(properties.getMinio().getBucketSkills(), skill.getOssPath());
        skillMapper.deleteById(id);
        cacheStore.evict(skill);
        skillRepository.invalidateVisibleCache();
        log.info("技能删除: skillKey={}, scope={}, byUser={}", skill.getSkillKey(), skill.getScope(), userId);
    }

    /**
     * 供 run 链路使用：按 skillKey 列表解析出当前用户可见的技能（公共或本人），不存在的忽略。
     */
    public List<SkillDO> resolveVisibleByKeys(Long userId, List<String> skillKeys) {
        if (skillKeys == null || skillKeys.isEmpty()) {
            return List.of();
        }
        return listVisible(userId).stream()
                .filter(s -> skillKeys.contains(s.getSkillKey()))
                .toList();
    }

    /** 当前用户可见的全部技能：公共在前、本人的在后（框架按 name 合并时后者胜，实现私有遮蔽同名公共）。 */
    List<SkillDO> listVisible(Long userId) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillDO>()
                .eq(SkillDO::getScope, SkillDO.SCOPE_PUBLIC)
                .or(w -> w.eq(SkillDO::getScope, SkillDO.SCOPE_USER)
                        .eq(SkillDO::getOwnerUserId, userId))
                .orderByAsc(SkillDO::getScope)
                .orderByAsc(SkillDO::getSkillKey));
    }

    private SkillDO requireVisible(Long userId, Long id) {
        SkillDO skill = skillMapper.selectById(id);
        if (skill == null || (!SkillDO.SCOPE_PUBLIC.equals(skill.getScope())
                && !isMine(skill, userId))) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        return skill;
    }

    private SkillDO findByScopeAndKey(boolean publicScope, Long ownerId, String skillKey) {
        LambdaQueryWrapper<SkillDO> wrapper = new LambdaQueryWrapper<SkillDO>()
                .eq(SkillDO::getSkillKey, skillKey)
                .eq(SkillDO::getScope, publicScope ? SkillDO.SCOPE_PUBLIC : SkillDO.SCOPE_USER);
        if (publicScope) {
            wrapper.isNull(SkillDO::getOwnerUserId);
        } else {
            wrapper.eq(SkillDO::getOwnerUserId, ownerId);
        }
        return skillMapper.selectOne(wrapper);
    }

    private boolean isAdmin(Long userId) {
        UserDO user = userMapper.selectById(userId);
        return user != null && UserRole.ADMIN.name().equals(user.getRole());
    }

    private boolean isMine(SkillDO skill, Long userId) {
        return SkillDO.SCOPE_USER.equals(skill.getScope()) && userId.equals(skill.getOwnerUserId());
    }

    private boolean matchesKeyword(SkillDO skill, String keyword) {
        String kw = keyword.toLowerCase(Locale.ROOT);
        return skill.getSkillKey().toLowerCase(Locale.ROOT).contains(kw)
                || skill.getDescription().toLowerCase(Locale.ROOT).contains(kw)
                || (skill.getTags() != null && skill.getTags().toLowerCase(Locale.ROOT).contains(kw));
    }

    private SkillSummaryResponse toSummary(SkillDO skill, Long userId, int fileCount) {
        return new SkillSummaryResponse(skill.getId(), skill.getSkillKey(), skill.getScope(),
                skill.getDescription(), splitTags(skill.getTags()), fileCount,
                skill.getTotalSize(), isMine(skill, userId), skill.getUpdatedAt());
    }

    private int fileCount(SkillDO skill) {
        return readJson(skill.getFilesJson()).size();
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split(",")).stream().map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    private String stringMeta(MarkdownSkillParser.ParsedMarkdown parsed, String key) {
        Object value = parsed.getMetadata().get(key);
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /** frontmatter 的 tags 兼容字符串列表与逗号分隔字符串两种写法 */
    private String tagsMeta(MarkdownSkillParser.ParsedMarkdown parsed) {
        Object value = parsed.getMetadata().get("tags");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(t -> !t.isEmpty()).reduce((a, b) -> a + "," + b).orElse(null);
        }
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private String writeJson(List<String> files) {
        try {
            return objectMapper.writeValueAsString(files);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private List<String> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
