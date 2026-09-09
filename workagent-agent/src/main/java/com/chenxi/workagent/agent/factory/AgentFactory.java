package com.chenxi.workagent.agent.factory;

import com.chenxi.workagent.agent.constant.AgentPrompts;
import com.chenxi.workagent.agent.tool.HttpRequestTool;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂（Factory + 缓存）：按 (userId, modelKey) 懒创建并 LRU 缓存 HarnessAgent。
 * 每个 HarnessAgent 均为无状态单例——请求态全部经 RuntimeContext(userId, sessionId) 外置，
 * 因此多实例部署下 resume 可由任意节点处理。
 * @author 辰夕
 */
@Slf4j
@Component
public class AgentFactory {

    private final ModelResolver modelResolver;
    private final AgentStateStore stateStore;
    private final WorkagentProperties properties;

    /** LRU 实例缓存，key = userId@modelKey */
    private final Map<String, HarnessAgent> cache;

    public AgentFactory(ModelResolver modelResolver, AgentStateStore stateStore,
                        WorkagentProperties properties) {
        this.modelResolver = modelResolver;
        this.stateStore = stateStore;
        this.properties = properties;
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
        log.info("创建 HarnessAgent 实例: userId={}, modelKey={}", userId, modelKey);
        return HarnessAgent.builder()
                .name("xiaozi")
                .sysPrompt(AgentPrompts.PLATFORM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(properties.getWorkspaceRoot())
                .stateStore(stateStore)
                .maxIters(properties.getAgent().getMaxIters())
                .build();
    }
}
