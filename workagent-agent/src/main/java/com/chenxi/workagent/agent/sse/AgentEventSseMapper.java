package com.chenxi.workagent.agent.sse;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import com.chenxi.workagent.infra.common.enums.SseEventType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 适配器：框架 AgentEvent → 前端 SSE 事件契约。
 * 不在映射表内的框架事件（如 MODEL_CALL_*）默认不转发，避免噪音。
 * @author 辰夕
 */
@Component
public class AgentEventSseMapper {

    private static final Map<AgentEventType, SseEventType> MAPPING = new EnumMap<>(AgentEventType.class);

    static {
        // 注意：AGENT_START 不映射——run.start 由 RunService 合成（seq=0，携带 runId/sessionId/modelKey），映射会重复
        MAPPING.put(AgentEventType.THINKING_BLOCK_START, SseEventType.THINKING_DELTA);
        MAPPING.put(AgentEventType.THINKING_BLOCK_DELTA, SseEventType.THINKING_DELTA);
        MAPPING.put(AgentEventType.THINKING_BLOCK_END, SseEventType.THINKING_DELTA);
        MAPPING.put(AgentEventType.TEXT_BLOCK_START, SseEventType.TEXT_DELTA);
        MAPPING.put(AgentEventType.TEXT_BLOCK_DELTA, SseEventType.TEXT_DELTA);
        MAPPING.put(AgentEventType.TEXT_BLOCK_END, SseEventType.TEXT_DELTA);
        MAPPING.put(AgentEventType.TOOL_CALL_START, SseEventType.TOOL_CALL);
        MAPPING.put(AgentEventType.TOOL_CALL_DELTA, SseEventType.TOOL_CALL);
        MAPPING.put(AgentEventType.TOOL_CALL_END, SseEventType.TOOL_CALL);
        MAPPING.put(AgentEventType.TOOL_RESULT_START, SseEventType.TOOL_RESULT);
        MAPPING.put(AgentEventType.TOOL_RESULT_TEXT_DELTA, SseEventType.TOOL_RESULT);
        MAPPING.put(AgentEventType.TOOL_RESULT_DATA_DELTA, SseEventType.TOOL_RESULT);
        MAPPING.put(AgentEventType.TOOL_RESULT_END, SseEventType.TOOL_RESULT);
        MAPPING.put(AgentEventType.REQUIRE_USER_CONFIRM, SseEventType.HITL_CONFIRM);
        MAPPING.put(AgentEventType.USER_CONFIRM_RESULT, SseEventType.HITL_CONFIRM_RESOLVED);
        MAPPING.put(AgentEventType.REQUIRE_EXTERNAL_EXECUTION, SseEventType.HITL_ASK_PARAM);
        MAPPING.put(AgentEventType.EXTERNAL_EXECUTION_RESULT, SseEventType.HITL_PARAM_RESOLVED);
        MAPPING.put(AgentEventType.EXCEED_MAX_ITERS, SseEventType.RUN_ERROR);
        MAPPING.put(AgentEventType.AGENT_END, SseEventType.RUN_END);
        MAPPING.put(AgentEventType.AGENT_RESULT, SseEventType.RUN_END);
        MAPPING.put(AgentEventType.CUSTOM, SseEventType.CUSTOM);
    }

    /**
     * 映射为 SSE 事件类型；不转发的事件返回 empty。
     */
    public Optional<SseEventType> toSseEventType(AgentEvent event) {
        return Optional.ofNullable(MAPPING.get(event.getType()));
    }
}
