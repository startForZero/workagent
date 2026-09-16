package com.chenxi.workagent.service.run.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * HITL 确认请求：用户对暂停的风险工具调用逐项给出允许/拒绝。
 *
 * @param confirmations 确认项列表（toolCallId 与挂起事件中的工具调用一一对应）
 * @author 辰夕
 */
public record ConfirmRequest(@NotEmpty List<@Valid Confirmation> confirmations) {

    /**
     * 单个工具调用的确认结果。
     *
     * @param toolCallId 工具调用 ID（来自 hitl.confirm 事件）
     * @param approved   true 允许执行；false 拒绝（模型将给出替代说明）
     */
    public record Confirmation(@NotNull String toolCallId, boolean approved) {
    }
}
