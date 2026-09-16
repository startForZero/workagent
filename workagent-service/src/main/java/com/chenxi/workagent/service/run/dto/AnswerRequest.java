package com.chenxi.workagent.service.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 参数补全提交：用户对 ask_user 挂起表单填写的值。
 * values 的 key 对应表单字段 key，值为用户填写内容（字符串/数字）。
 * @author 辰夕
 */
public record AnswerRequest(
        @NotBlank String toolCallId,
        @NotNull Map<String, Object> values) {
}
