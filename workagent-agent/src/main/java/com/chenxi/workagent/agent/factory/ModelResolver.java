package com.chenxi.workagent.agent.factory;

import io.agentscope.core.model.Model;

/**
 * 模型解析策略接口（由 service 层实现）：按用户与 modelKey 解析出已就绪的模型实例。
 * 定义在 agent 层以避免 service → agent 反向依赖成环。
 * @author 辰夕
 */
public interface ModelResolver {

    /**
     * 解析有效 modelKey：入参为空时返回该用户默认模型（或平台默认模型）的 modelKey。
     * AgentFactory 以此作为缓存键的一部分，保证模型变更后可精确失效。
     */
    String effectiveModelKey(Long userId, String modelKey);

    /**
     * 解析模型实例。
     *
     * @param userId   用户 ID
     * @param modelKey provider:model 形式（须为 effectiveModelKey 的返回值）
     * @return 可直接装配进 HarnessAgent 的 Model
     */
    Model resolve(Long userId, String modelKey);
}
