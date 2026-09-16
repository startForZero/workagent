package com.chenxi.workagent.service.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.SkillDO;
import com.chenxi.workagent.infra.mapper.SkillMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.harness.agent.skill.RuntimeContextSkillRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 技能仓库框架适配（M3）：实现 RuntimeContextSkillRepository，按 RuntimeContext.userId
 * 返回「公共 + 该用户」的可见技能，框架中间件据此渲染 &lt;available_skills&gt; 并物化进
 * 工作区 .skills-cache（沙箱经投影可见）。
 *
 * <p>性能：框架每轮 system-prompt pass 都会调 {@link #getAllSkills(RuntimeContext)}，
 * 因此可见列表按 userId 短 TTL 缓存（workagent.skill.list-cache-seconds），
 * AgentSkill 按 (skillKey+etag) 内存缓存——ETag 不变即零 IO。
 *
 * <p>本仓库对框架只读（技能写操作走 SkillService 导入/删除，不经框架 save/delete）。
 * @author 辰夕
 */
@Slf4j
@Component
public class WorkagentSkillRepository implements RuntimeContextSkillRepository {

    /** 技能 source 标识：skillId 形如 {name}_workagent */
    public static final String SOURCE = "workagent";

    private final SkillMapper skillMapper;
    private final SkillCacheStore cacheStore;
    private final WorkagentProperties properties;

    private volatile boolean writeable = false;

    /** 可见技能行缓存：userId → (截止时间, 列表) */
    private final Map<Long, CacheEntry<List<SkillDO>>> visibleCache = new ConcurrentHashMap<>();
    /** AgentSkill 缓存：(scope/owner/skillKey) → (etag, skill)；etag 变化即重建 */
    private final Map<String, CacheEntry<AgentSkill>> skillCache = new ConcurrentHashMap<>();

    public WorkagentSkillRepository(SkillMapper skillMapper, SkillCacheStore cacheStore,
                                    WorkagentProperties properties) {
        this.skillMapper = skillMapper;
        this.cacheStore = cacheStore;
        this.properties = properties;
    }

    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        Long userId = parseUserId(context);
        if (userId == null) {
            // 无用户身份时仅暴露公共技能（兜底，正常 run 链路必有 userId）
            return List.of();
        }
        List<AgentSkill> out = new ArrayList<>();
        for (SkillDO row : visibleSkills(userId)) {
            try {
                out.add(loadCached(row));
            } catch (Exception e) {
                // 单个技能损坏/物化失败不拖垮整次调用
                log.warn("技能加载失败，已跳过: skillKey={}, err={}", row.getSkillKey(), e.getMessage());
            }
        }
        return out;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        // 上下文无关调用（框架兼容路径）：不暴露任何用户技能
        return List.of();
    }

    @Override
    public AgentSkill getSkill(String name) {
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.of();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return false;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("minio", properties.getSkillCacheRoot(), writeable);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    /**
     * 技能数据变更（导入覆盖/删除）后调用：清空可见列表缓存。
     * AgentSkill 缓存按 etag 比对自动失效，无需清理。
     */
    public void invalidateVisibleCache() {
        visibleCache.clear();
    }

    // ---------------------------------------------------------------------
    //  内部：两级缓存
    // ---------------------------------------------------------------------

    private List<SkillDO> visibleSkills(Long userId) {
        long ttlMillis = properties.getSkill().getListCacheSeconds() * 1000L;
        CacheEntry<List<SkillDO>> cached = visibleCache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAtMillis > now) {
            return cached.value;
        }
        // 公共在前、本人的在后：框架按 name 合并时后者胜，实现私有技能遮蔽同名公共技能
        List<SkillDO> rows = skillMapper.selectList(new LambdaQueryWrapper<SkillDO>()
                .eq(SkillDO::getScope, SkillDO.SCOPE_PUBLIC)
                .or(w -> w.eq(SkillDO::getScope, SkillDO.SCOPE_USER)
                        .eq(SkillDO::getOwnerUserId, userId))
                .orderByAsc(SkillDO::getScope)
                .orderByAsc(SkillDO::getSkillKey));
        visibleCache.put(userId, new CacheEntry<>(rows, now + ttlMillis));
        return rows;
    }

    private AgentSkill loadCached(SkillDO row) {
        String cacheKey = row.getScope() + "/" + row.getOwnerUserId() + "/" + row.getSkillKey();
        CacheEntry<AgentSkill> cached = skillCache.get(cacheKey);
        if (cached != null && row.getEtag().equals(cached.etag)) {
            return cached.value;
        }
        synchronized (cacheKey.intern()) {
            cached = skillCache.get(cacheKey);
            if (cached != null && row.getEtag().equals(cached.etag)) {
                return cached.value;
            }
            Path dir = cacheStore.ensureCached(row);
            // 全量读资源进内存：框架 MarketplaceStager 据此物化进工作区 .skills-cache
            AgentSkill skill = SkillFileSystemHelper.loadSkillFromDirectory(dir, SOURCE, true);
            log.info("技能物化加载: skillKey={}, dir={}, resources={}", row.getSkillKey(), dir,
                    skill.getResources().keySet());
            skillCache.put(cacheKey, new CacheEntry<>(skill, 0L, row.getEtag()));
            return skill;
        }
    }

    private Long parseUserId(RuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(context.getUserId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 简单缓存条目：expireAtMillis 用于 TTL 缓存；etag 用于版本指纹比对（仅 skillCache 用） */
    private record CacheEntry<T>(T value, long expireAtMillis, String etag) {
        CacheEntry(T value, long expireAtMillis) {
            this(value, expireAtMillis, null);
        }
    }
}
