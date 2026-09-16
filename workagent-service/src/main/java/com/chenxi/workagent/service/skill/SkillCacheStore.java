package com.chenxi.workagent.service.skill;

import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.SkillDO;
import com.chenxi.workagent.infra.storage.MinioStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 技能包本地缓存：把 MinIO 中的技能 zip 物化到本地目录（skillCacheRoot），ETag 判失效。
 * 运行时的技能仓库（WorkagentSkillRepository）与详情预览（SkillService）共用本组件。
 *
 * <p>目录布局：{skillCacheRoot}/public/{skillKey}/ 或 {skillCacheRoot}/users/{userId}/{skillKey}/；
 * ETag 标记为同级 sibling 文件 {skillKey}.etag（不放进技能目录，避免被当成技能资源）。
 * @author 辰夕
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCacheStore {

    /** ETag 标记文件扩展名（与技能目录同级的 sibling 文件） */
    private static final String ETAG_FILE_SUFFIX = ".etag";

    private final MinioStorageService storageService;
    private final WorkagentProperties properties;

    /** 物化锁：key = scope/owner/skillKey */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 确保本地缓存与 MinIO 当前版本一致，返回技能目录（含 SKILL.md）。
     * ETag 一致且目录完整时零 IO 直接命中。
     */
    public Path ensureCached(SkillDO skill) {
        Path dir = cacheDir(skill);
        Path etagFile = etagFile(skill);
        if (etagMatches(etagFile, skill.getEtag()) && Files.isRegularFile(dir.resolve("SKILL.md"))) {
            return dir;
        }
        String lockKey = skill.getScope() + "/" + skill.getOwnerUserId() + "/" + skill.getSkillKey();
        synchronized (locks.computeIfAbsent(lockKey, k -> new Object())) {
            // 双重检查：等待锁期间可能已被另一线程物化
            if (etagMatches(etagFile, skill.getEtag()) && Files.isRegularFile(dir.resolve("SKILL.md"))) {
                return dir;
            }
            log.info("技能本地缓存物化: skillKey={}, etag={}", skill.getSkillKey(), skill.getEtag());
            try (InputStream in = storageService.get(properties.getMinio().getBucketSkills(),
                    skill.getOssPath())) {
                Path staging = Files.createTempDirectory("skill-stage-");
                try {
                    unzip(in, staging);
                    // 历史包可能多包一层文件夹，物化时同样归一（新导入的包已在入口归一化）
                    flattenWrapperIfNeeded(staging);
                    replaceDir(staging, dir);
                    Files.writeString(etagFile, skill.getEtag());
                } finally {
                    deleteQuietly(staging);
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.error("技能缓存物化失败: skillKey={}", skill.getSkillKey(), e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "技能包加载失败");
            }
            return dir;
        }
    }

    /** 直接把导入时解压好的目录安装为缓存（importZip 已校验过内容），并写 ETag 标记。 */
    public void install(SkillDO skill, Path unzippedDir) {
        String lockKey = skill.getScope() + "/" + skill.getOwnerUserId() + "/" + skill.getSkillKey();
        synchronized (locks.computeIfAbsent(lockKey, k -> new Object())) {
            try {
                replaceDir(unzippedDir, cacheDir(skill));
                Files.writeString(etagFile(skill), skill.getEtag());
            } catch (IOException e) {
                log.error("技能缓存安装失败: skillKey={}", skill.getSkillKey(), e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "技能包加载失败");
            }
        }
    }

    /** 清除本地缓存（删除/覆盖导入后调用）。 */
    public void evict(SkillDO skill) {
        deleteQuietly(cacheDir(skill));
        deleteQuietly(etagFile(skill));
    }

    /** 技能缓存目录（不一定已物化）。 */
    public Path cacheDir(SkillDO skill) {
        Path root = Path.of(properties.getSkillCacheRoot());
        if (SkillDO.SCOPE_PUBLIC.equals(skill.getScope())) {
            return root.resolve("public").resolve(skill.getSkillKey());
        }
        return root.resolve("users").resolve(String.valueOf(skill.getOwnerUserId()))
                .resolve(skill.getSkillKey());
    }

    private Path etagFile(SkillDO skill) {
        Path dir = cacheDir(skill);
        return dir.getParent().resolve(skill.getSkillKey() + ETAG_FILE_SUFFIX);
    }

    private boolean etagMatches(Path etagFile, String etag) {
        try {
            return Files.isRegularFile(etagFile) && Files.readString(etagFile).trim().equals(etag);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 安全解压 zip 到目标目录：zip-slip 防护 + 文件数/解压总量限制（防 zip 炸弹）。
     *
     * @return 包内相对路径列表（仅文件）
     */
    public List<String> unzip(InputStream zipIn, Path destDir) throws IOException {
        long maxFiles = properties.getSkill().getMaxFiles();
        long maxTotalBytes = properties.getSkill().getMaxSizeMb() * 1024 * 1024 * 4; // 解压总量按压缩上限 4 倍兜底
        List<String> paths = new ArrayList<>();
        long total = 0;
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(zipIn)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.startsWith("/")) {
                    throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
                }
                Path target = destDir.resolve(name).normalize();
                if (!target.startsWith(destDir)) {
                    // zip-slip：条目路径逃逸出目标目录
                    throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (paths.size() >= maxFiles) {
                    throw new BizException(ErrorCode.SKILL_IMPORT_INVALID);
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                total += Files.size(target);
                if (total > maxTotalBytes) {
                    throw new BizException(ErrorCode.SKILL_TOO_LARGE);
                }
                paths.add(name);
            }
        }
        return paths;
    }

    private void replaceDir(Path source, Path target) throws IOException {
        deleteQuietly(target);
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 归一化「多包一层文件夹」的 zip：直接压缩文件夹产出的包根下没有 SKILL.md，
     * 唯一有效顶层目录（忽略 __MACOSX 与点开头条目）里才是完整技能 → 内容上提一层。
     *
     * @return true=根下已有 SKILL.md（无需处理或已成功上提）；false=确实缺 SKILL.md
     */
    public boolean flattenWrapperIfNeeded(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve("SKILL.md"))) {
            return true;
        }
        // macOS「压缩」自动附带的元数据目录，直接清掉
        deleteQuietly(dir.resolve("__MACOSX"));
        List<Path> top;
        try (var stream = Files.list(dir)) {
            top = stream.filter(p -> !p.getFileName().toString().startsWith(".")).toList();
        }
        if (top.size() != 1 || !Files.isDirectory(top.get(0))
                || !Files.isRegularFile(top.get(0).resolve("SKILL.md"))) {
            return false;
        }
        Path wrapper = top.get(0);
        try (var stream = Files.list(wrapper)) {
            for (Path child : stream.toList()) {
                Files.move(child, dir.resolve(child.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.delete(wrapper);
        return true;
    }

    /** 列出目录下全部文件（相对路径，正斜杠，忽略点开头条目），供 files_json 使用 */
    public List<String> listFiles(Path dir) throws IOException {
        List<String> paths = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !dir.relativize(p).getFileName().toString().startsWith("."))
                    .forEach(p -> paths.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        paths.sort(String::compareTo);
        return paths;
    }

    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            });
        } catch (IOException ignored) {
            // 尽力清理
        }
    }
}
