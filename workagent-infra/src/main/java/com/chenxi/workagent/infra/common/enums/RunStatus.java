package com.chenxi.workagent.infra.common.enums;

/**
 * 运行（run）生命周期状态机。迁移校验集中在 RunService。
 * @author 辰夕
 */
public enum RunStatus {
    RUNNING,
    WAITING_INPUT,
    WAITING_CONFIRM,
    DONE,
    ERROR,
    CANCELLED,
    TIMEOUT
}
