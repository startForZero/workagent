package com.chenxi.workagent.agent.sse;

import com.chenxi.workagent.infra.common.enums.SseEventType;

/**
 * SSE 统一事件包络：{event, runId, seq, data}，与前端共享契约。
 *
 * @param event 事件名（SseEventType.getEventName）
 * @param runId 运行标识
 * @param seq   运行内递增序号（断线重连补发依据）
 * @param data  事件负载
 * @author 辰夕
 */
public record SseEnvelope(String event, String runId, long seq, Object data) {

    public static SseEnvelope of(SseEventType type, String runId, long seq, Object data) {
        return new SseEnvelope(type.getEventName(), runId, seq, data);
    }
}
