package com.chenxi.workagent.agent.factory;

import com.chenxi.workagent.agent.constant.AgentPrompts;
import com.chenxi.workagent.agent.sandbox.DockerProbe;
import com.chenxi.workagent.agent.tool.AskUserTool;
import com.chenxi.workagent.agent.tool.HttpRequestTool;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂（Factory + 缓存）：按 (userId, modelKey) 懒创建并 LRU 缓存 HarnessAgent。
 * 每个 HarnessAgent 均为无状态单例——请求态全部经 RuntimeContext(userId, sessionId) 外置，
 * 因此多实例部署下 resume 可由任意节点处理。
 * M2：装配 Docker 沙箱（SESSION 隔离）、deliver_artifact 产物归档；M3：权限 BYPASS 全放行 + ask_user 参数补全
 * + MinIO 技能仓库（service 模块实现，ObjectProvider 注入打破 Maven 环依赖）。
 * @author 辰夕
 */
@Slf4j
@Component
public class AgentFactory {

    private final ModelResolver modelResolver;
    private final AgentStateStore stateStore;
    private final WorkagentProperties properties;
    private final DockerProbe dockerProbe;
    /** 产物投递目标在 service 模块实现，用 ObjectProvider 打破 agent→service 的 Maven 环依赖 */
    private final ObjectProvider<ArtifactDeliveryTarget> artifactDeliveryTarget;
    /** 技能仓库同样在 service 模块实现（M3 MinIO 技能市场） */
    private final ObjectProvider<AgentSkillRepository> skillRepositoryProvider;

    /** LRU 实例缓存，key = userId@modelKey */
    private final Map<String, HarnessAgent> cache;

    public AgentFactory(ModelResolver modelResolver, AgentStateStore stateStore,
                        WorkagentProperties properties, DockerProbe dockerProbe,
                        ObjectProvider<ArtifactDeliveryTarget> artifactDeliveryTarget,
                        ObjectProvider<AgentSkillRepository> skillRepositoryProvider) {
        this.modelResolver = modelResolver;
        this.stateStore = stateStore;
        this.properties = properties;
        this.dockerProbe = dockerProbe;
        this.artifactDeliveryTarget = artifactDeliveryTarget;
        this.skillRepositoryProvider = skillRepositoryProvider;
        int maxSize = properties.getAgent().getInstanceCacheSize();
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, HarnessAgent> eldest) {
                if (size() > maxSize) {
                    log.info("Agent 实例 LRU 淘汰: {}", eldest.getKey());
                    eldest.getValue().close();
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * 获取（或创建）指定用户与模型的 Agent 实例。
     *
     * @param userId   用户 ID
     * @param modelKey provider:model；为空时由 ModelResolver 落默认模型
     */
    public synchronized HarnessAgent get(Long userId, String modelKey) {
        String effective = modelResolver.effectiveModelKey(userId, modelKey);
        return cache.computeIfAbsent(cacheKey(userId, effective), key -> build(userId, effective));
    }

    /**
     * 使指定用户某模型的实例失效（模型改 Key/删除/停用时调用）。
     */
    public synchronized void evict(Long userId, String modelKey) {
        HarnessAgent removed = cache.remove(cacheKey(userId, modelResolver.effectiveModelKey(userId, modelKey)));
        if (removed != null) {
            removed.close();
        }
    }

    private String cacheKey(Long userId, String effectiveModelKey) {
        return userId + "@" + effectiveModelKey;
    }

    private HarnessAgent build(Long userId, String modelKey) {
        Model model = modelResolver.resolve(userId, modelKey);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new HttpRequestTool());
        toolkit.registerTool(new AskUserTool());
        WorkagentProperties.Sandbox sandbox = properties.getSandbox();
        boolean sandboxReady = sandbox.isEnabled() && dockerProbe.available();
        if (sandbox.isEnabled() && !sandboxReady) {
            log.warn("沙箱已启用但 Docker 不可用，Agent 退化为宿主机本地文件系统: userId={}", userId);
        }
        log.info("创建 HarnessAgent 实例: userId={}, modelKey={}, sandbox={}", userId, modelKey, sandboxReady);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("xiaozi")
                .sysPrompt(AgentPrompts.PLATFORM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                // 必须归一化为绝对路径：框架 MarketplaceStager 用 startsWith 做目录逃逸校验，
                // 相对路径（如 ./data/...）会被误判为越界导致技能资源静默跳过（M3 实测踩坑）
                .workspace(Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize())
                .stateStore(stateStore)
                .maxIters(properties.getAgent().getMaxIters())
                .permissionContext(buildPermissionContext());
        if (sandboxReady) {
            builder.filesystem(buildSandboxFilesystemSpec(sandbox));
        }
        ArtifactDeliveryTarget deliveryTarget = artifactDeliveryTarget.getIfAvailable();
        if (deliveryTarget != null) {
            builder.artifactDeliveryTarget(deliveryTarget);
        }
        // M3 技能仓库：注入后框架自动装配技能中间件（<available_skills> 目录 + load_skill_through_path 工具）
        AgentSkillRepository skillRepository = skillRepositoryProvider.getIfAvailable();
        if (skillRepository != null) {
            builder.skillRepository(skillRepository);
        }
        return builder.build();
    }

    /**
     * 权限策略：BYPASS 全放行（2026-09 产品决策：工具调用不再弹确认——文件类操作本就安全，
     * execute/shell_execute 跑在断网限额的一次性沙箱里，沙箱即安全边界）。
     * 人机交互只保留「参数补全」一条链路（ask_user 工具挂起 → 用户填表 → 续跑）。
     */
    private PermissionContextState buildPermissionContext() {
        return PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
    }

    /** Docker 沙箱文件系统：SESSION 隔离、工作区投影、资源限额与断网均由配置决定 */
    private DockerFilesystemSpec buildSandboxFilesystemSpec(WorkagentProperties.Sandbox sandbox) {
        DockerFilesystemSpec spec = new DockerFilesystemSpec()
                .image(sandbox.getImage())
                .cpuCount(sandbox.getCpuCount())
                .memorySizeBytes(sandbox.getMemorySizeBytes())
                .network(sandbox.getNetwork());
        // isolationScope 定义在父类 SandboxFilesystemSpec，返回父类型，须最后调用
        spec.isolationScope(IsolationScope.SESSION);
        return spec;
    }
}
