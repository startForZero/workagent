package com.chenxi.workagent.infra.common.constant;

/**
 * Redis key 统一约定。
 * @author 辰夕
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    private static final String SEP = ":";

    /** run 事件缓存（SSE 断线补发）：wa:run:events:{runId} */
    public static String runEvents(String runId) {
        return "wa" + SEP + "run" + SEP + "events" + SEP + runId;
    }

    /** run 状态：wa:run:status:{runId} */
    public static String runStatus(String runId) {
        return "wa" + SEP + "run" + SEP + "status" + SEP + runId;
    }
}
