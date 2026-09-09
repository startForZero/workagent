package com.chenxi.workagent.infra.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * SSE 事件类型契约（与前端共享），event 名禁止散落字符串。
 * @author 辰夕
 */
public enum SseEventType {

    RUN_START("run.start"),
    THINKING_DELTA("thinking.delta"),
    TEXT_DELTA("text.delta"),
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),
    HITL_CONFIRM("hitl.confirm"),
    HITL_ASK_PARAM("hitl.ask_param"),
    ARTIFACT_CREATED("artifact.created"),
    RUN_END("run.end"),
    RUN_ERROR("run.error"),
    RUN_TIMEOUT("run.timeout"),
    CUSTOM("custom");

    private final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }

    @JsonValue
    public String getEventName() {
        return eventName;
    }
}
