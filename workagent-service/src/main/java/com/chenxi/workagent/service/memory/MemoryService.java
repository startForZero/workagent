package com.chenxi.workagent.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.SessionDO;
import com.chenxi.workagent.infra.entity.UserMemoryDO;
import com.chenxi.workagent.infra.mapper.SessionMapper;
import com.chenxi.workagent.infra.mapper.UserMemoryMapper;
import com.chenxi.workagent.service.memory.dto.MemoryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆中心服务（M4）：MEMORY.md 文件为真相源（框架 memory_save/flush/consolidator 维护），
 * wa_user_memory 表仅作 UI 镜像 + 来源会话元数据。
 *
 * <p>三段式同步：
 * <ul>
 *   <li><b>read-repair</b>：{@link #list} 时解析文件 bullets 与表对账（文件有表无→补录，
 *       表有文件无→视为被 consolidator 正常归纳合并，删行）</li>
 *   <li><b>事件回填</b>：{@link #recordFromMemorySave} 由 RunService 在 memory_save
 *       工具成功后调用，补录条目并回填来源会话</li>
 *   <li><b>写穿</b>：编辑/删除/清空先改文件（锁内原子重写）成功再改表</li>
 * </ul>
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryMapper memoryMapper;
    private final SessionMapper sessionMapper;
    private final MemoryFileStore fileStore;
    private final WorkagentProperties properties;
    private final ObjectMapper objectMapper;

    /** 列表：先 read-repair 对账，再返回表数据（带来源会话标题）。 */
    @Transactional
    public List<MemoryResponse> list(Long userId) {
        reconcile(userId);
        List<UserMemoryDO> rows = memoryMapper.selectList(new LambdaQueryWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getUserId, userId)
                .orderByDesc(UserMemoryDO::getUpdatedAt));
        Map<Long, String> sessionTitles = loadSessionTitles(rows);
        return rows.stream()
                .map(r -> new MemoryResponse(r.getId(), r.getContent(), r.getSourceSessionId(),
                        r.getSourceSessionId() == null ? null : sessionTitles.get(r.getSourceSessionId()),
                        r.getCreatedAt(), r.getUpdatedAt()))
                .toList();
    }

    /** 编辑：先改文件（锁内原子重写）成功再改表。 */
    @Transactional
    public void update(Long userId, Long id, String content) {
        validateContent(content);
        UserMemoryDO row = requireOwned(userId, id);
        String newContent = content.trim();
        if (newContent.equals(row.getContent())) {
            return;
        }
        fileStore.replaceEntry(userId, row.getContent(), newContent);
        row.setContent(newContent);
        row.setContentHash(contentHash(newContent));
        row.setUpdatedAt(LocalDateTime.now());
        try {
            memoryMapper.updateById(row);
        } catch (DuplicateKeyException e) {
            // 新内容与已有条目重复：文件已改但表撞 uk——回滚文件，提示用户
            fileStore.replaceEntry(userId, newContent, row.getContent());
            throw new BizException(ErrorCode.PARAM_INVALID, "已存在相同内容的记忆");
        }
    }

    /** 删除：先删文件行再删表行；文件行已被 consolidator 合并（找不到）时容错直删表行。 */
    @Transactional
    public void delete(Long userId, Long id) {
        UserMemoryDO row = requireOwned(userId, id);
        try {
            fileStore.removeEntry(userId, row.getContent());
        } catch (BizException e) {
            if (e.getErrorCode() != ErrorCode.MEMORY_NOT_FOUND) {
                throw e;
            }
            log.info("记忆文件条目已不存在（或已被归纳合并），仅删表行: id={}", id);
        }
        memoryMapper.deleteById(id);
    }

    /** 一键清空：MEMORY.md 清空 + memory/ 每日流水全删 + 表清空；不清会话转录（属会话历史）。 */
    @Transactional
    public void clearAll(Long userId) {
        fileStore.clearAll(userId);
        memoryMapper.delete(new LambdaQueryWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getUserId, userId));
        log.info("用户记忆已一键清空: userId={}", userId);
    }

    /**
     * RunService 事件流拦截回填：解析 memory_save 参数 content 拆 bullets 入库并填来源会话。
     * uk 冲突即跳过（幂等）；任何异常仅记 warn，绝不影响主对话流。
     */
    public void recordFromMemorySave(Long userId, Long sessionPk, String argsJson) {
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            String content = args.path("content").asText("");
            if (content.isBlank()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            for (String bullet : fileStore.parseBullets(content)) {
                if (bullet.length() > properties.getMemory().getMaxEntryLength()) {
                    continue;
                }
                UserMemoryDO row = new UserMemoryDO();
                row.setUserId(userId);
                row.setContent(bullet);
                row.setContentHash(contentHash(bullet));
                row.setSourceSessionId(sessionPk);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                try {
                    memoryMapper.insert(row);
                } catch (DuplicateKeyException e) {
                    // 同内容已存在：幂等跳过
                }
            }
        } catch (Exception e) {
            log.warn("memory_save 回填失败（不影响对话）: userId={}, {}", userId, e.getMessage());
        }
    }

    /** 预留：用户注销时物理删除记忆目录 + 表行（接入点待账号注销功能）。 */
    @Transactional
    public void purgeUser(Long userId) {
        fileStore.purgeUser(userId);
        memoryMapper.delete(new LambdaQueryWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getUserId, userId));
    }

    /**
     * read-repair 对账：以 MEMORY.md bullets 为准，文件有表无→补录（来源 NULL），
     * 表有文件无→删除（视为 consolidator 正常归纳合并），共有→保留表内元数据。
     */
    private void reconcile(Long userId) {
        List<String> bullets = fileStore.parseBullets(fileStore.read(userId));
        List<UserMemoryDO> rows = memoryMapper.selectList(new LambdaQueryWrapper<UserMemoryDO>()
                .eq(UserMemoryDO::getUserId, userId));
        Map<String, UserMemoryDO> rowByHash = rows.stream()
                .collect(Collectors.toMap(UserMemoryDO::getContentHash, Function.identity(), (a, b) -> a));

        List<String> fileHashes = new ArrayList<>(bullets.size());
        LocalDateTime now = LocalDateTime.now();
        for (String bullet : bullets) {
            if (bullet.length() > properties.getMemory().getMaxEntryLength()) {
                continue;
            }
            String hash = contentHash(bullet);
            fileHashes.add(hash);
            if (!rowByHash.containsKey(hash)) {
                UserMemoryDO row = new UserMemoryDO();
                row.setUserId(userId);
                row.setContent(bullet);
                row.setContentHash(hash);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                try {
                    memoryMapper.insert(row);
                } catch (DuplicateKeyException e) {
                    // 并发对账/回填竞态：uk 兜底，跳过即可
                }
            }
        }
        List<Long> staleIds = rows.stream()
                .filter(r -> !fileHashes.contains(r.getContentHash()))
                .map(UserMemoryDO::getId)
                .toList();
        if (!staleIds.isEmpty()) {
            memoryMapper.deleteBatchIds(staleIds);
        }
    }

    private Map<Long, String> loadSessionTitles(List<UserMemoryDO> rows) {
        List<Long> sessionIds = rows.stream()
                .map(UserMemoryDO::getSourceSessionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return sessionMapper.selectBatchIds(sessionIds).stream()
                .collect(Collectors.toMap(SessionDO::getId, SessionDO::getTitle));
    }

    private UserMemoryDO requireOwned(Long userId, Long id) {
        UserMemoryDO row = memoryMapper.selectById(id);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        return row;
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.MEMORY_CONTENT_EMPTY);
        }
        if (content.trim().length() > properties.getMemory().getMaxEntryLength()) {
            throw new BizException(ErrorCode.MEMORY_TOO_LONG);
        }
    }

    /** SHA-256 hex（对账与幂等键，与 uk_user_hash 对应） */
    static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
