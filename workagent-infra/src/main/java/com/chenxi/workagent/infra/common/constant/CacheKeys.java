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

    /** HITL 确认快照（RequireUserConfirmEvent 的 replyId + toolCalls）：wa:run:hitl:{runId} */
    public static String runHitl(String runId) {
        return "wa" + SEP + "run" + SEP + "hitl" + SEP + runId;
    }

    /** 参数补全快照（RequireExternalExecutionEvent 的 replyId + toolCalls）：wa:run:param:{runId} */
    public static String runParam(String runId) {
        return "wa" + SEP + "run" + SEP + "param" + SEP + runId;
    }

    /** @ 唤起技能快照（run 的 skillKeys，续跑时重放 SkillFilter）：wa:run:skills:{runId} */
    public static String runSkills(String runId) {
        return "wa" + SEP + "run" + SEP + "skills" + SEP + runId;
    }
}
