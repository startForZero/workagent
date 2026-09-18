package com.chenxi.workagent.service.memory;

import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户长期记忆文件存取（M4）：MEMORY.md 是框架维护的真相源，本组件负责记忆中心
 * 编辑/删除/清空时的安全改写。
 *
 * <p>目录布局：{@code <memory.root>/<userId>/MEMORY.md} + {@code memory/YYYY-MM-DD.md}
 * 每日流水（与框架 MemoryFlushManager/MemoryConsolidator 经 USER 命名空间路由后的落盘
 * 位置一致）。
 *
 * <p>并发：按 userId 进程内锁串行化（照 SkillCacheStore 模式），所有改写走
 * 「同目录 tmp + ATOMIC_MOVE」原子替换，杜绝半写文件。与框架 consolidator 的
 * read-modify-write 存在理论小窗口冲突（一方丢更新），接受并以原子写把损坏风险降为零。
 * @author 辰夕
 */
@Slf4j
@Component
public class MemoryFileStore {

    /** 框架约定的长期记忆主文件名（WorkspaceConstants.MEMORY_MD） */
    private static final String MEMORY_MD = "MEMORY.md";
    /** 框架约定的每日流水目录名（WorkspaceConstants.MEMORY_DIR） */
    private static final String MEMORY_DIR = "memory";

    private final Path root;

    /** 改写锁：key = userId（框架 pathLocks 是 fs 实例级，service 侧自行串行化） */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public MemoryFileStore(WorkagentProperties properties) {
        this.root = Path.of(properties.getMemory().getRoot()).toAbsolutePath().normalize();
    }

    public Path memoryMdPath(Long userId) {
        return root.resolve(String.valueOf(userId)).resolve(MEMORY_MD);
    }

    public Path dailyDir(Long userId) {
        return root.resolve(String.valueOf(userId)).resolve(MEMORY_DIR);
    }

    /**
     * 解析 markdown 中的记忆条目：行 strip 后以 {@code - }/{@code * } 开头算一条
     * （去前缀 trim）；忽略标题、空行与非 bullet 续行（容错 consolidator 的自由格式）。
     */
    public List<String> parseBullets(String markdown) {
        List<String> bullets = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return bullets;
        }
        for (String line : markdown.split("\n")) {
            String stripped = line.strip();
            String content = null;
            if (stripped.startsWith("- ")) {
                content = stripped.substring(2).trim();
            } else if (stripped.startsWith("* ")) {
                content = stripped.substring(2).trim();
            }
            if (content != null && !content.isEmpty()) {
                bullets.add(content);
            }
        }
        return bullets;
    }

    /** 读取 MEMORY.md 全文；文件不存在返回空串。 */
    public String read(Long userId) {
        Path path = memoryMdPath(userId);
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("读取记忆文件失败: userId={}, {}", userId, e.getMessage());
            throw new BizException(ErrorCode.MEMORY_FILE_ERROR);
        }
    }

    /** 编辑单条：定位内容精确匹配的 bullet 行并替换为新内容；找不到抛 MEMORY_NOT_FOUND。 */
    public void replaceEntry(Long userId, String oldContent, String newContent) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            String[] lines = read(userId).split("\n", -1);
            boolean found = false;
            for (int i = 0; i < lines.length; i++) {
                String stripped = lines[i].strip();
                String prefix = bulletPrefix(stripped);
                if (prefix != null && stripped.substring(prefix.length()).trim().equals(oldContent)) {
                    lines[i] = prefix + newContent;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new BizException(ErrorCode.MEMORY_NOT_FOUND, "记忆文件中的条目已变更，请刷新后重试");
            }
            atomicWrite(memoryMdPath(userId), String.join("\n", lines));
        }
    }

    /** 删除单条：移除内容精确匹配的 bullet 行；找不到抛 MEMORY_NOT_FOUND。 */
    public void removeEntry(Long userId, String content) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            String[] lines = read(userId).split("\n", -1);
            List<String> kept = new ArrayList<>(lines.length);
            boolean found = false;
            for (String line : lines) {
                String stripped = line.strip();
                String prefix = bulletPrefix(stripped);
                if (!found && prefix != null
                        && stripped.substring(prefix.length()).trim().equals(content)) {
                    found = true;
                    continue;
                }
                kept.add(line);
            }
            if (!found) {
                throw new BizException(ErrorCode.MEMORY_NOT_FOUND, "记忆文件中的条目已变更，请刷新后重试");
            }
            atomicWrite(memoryMdPath(userId), String.join("\n", kept));
        }
    }

    /**
     * 一键清空：MEMORY.md 重写为空 + 删除 memory/ 下全部每日流水文件。
     * 不动 agents/ 会话转录（属会话历史，与会话列表共存亡）。
     */
    public void clearAll(Long userId) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            atomicWrite(memoryMdPath(userId), "");
            Path dir = dailyDir(userId);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("删除每日记忆文件失败: {}, {}", p, e.getMessage());
                                }
                            });
                } catch (IOException e) {
                    log.warn("列出每日记忆目录失败: {}, {}", dir, e.getMessage());
                }
            }
        }
    }

    /** 物理删除用户全部记忆目录（预留：账号注销时调用）。 */
    public void purgeUser(Long userId) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            Path userDir = root.resolve(String.valueOf(userId));
            if (!Files.isDirectory(userDir)) {
                return;
            }
            try (var walk = Files.walk(userDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("清理用户记忆目录失败: {}, {}", p, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.warn("清理用户记忆目录失败: userId={}, {}", userId, e.getMessage());
            }
        }
    }

    /** 返回 "- "/"* " 前缀（含空格），非 bullet 行返回 null */
    private static String bulletPrefix(String strippedLine) {
        if (strippedLine.startsWith("- ")) {
            return "- ";
        }
        if (strippedLine.startsWith("* ")) {
            return "* ";
        }
        return null;
    }

    /** 同目录 tmp 文件 + ATOMIC_MOVE 原子替换（不支持时退化为普通移动），杜绝半写文件 */
    private void atomicWrite(Path target, String content) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp." + UUID.randomUUID());
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(tmp, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            log.warn("写入记忆文件失败: {}, {}", target, e.getMessage());
            throw new BizException(ErrorCode.MEMORY_FILE_ERROR);
        }
    }
}
