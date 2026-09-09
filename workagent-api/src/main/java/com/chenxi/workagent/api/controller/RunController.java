package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.agent.sse.SseEnvelope;
import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.run.RunService;
import com.chenxi.workagent.service.run.dto.RunRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 运行接口：POST /api/runs 返回 SSE 流（自研事件协议，包络 {event, runId, seq, data}）。
 * @author 辰夕
 */
@Slf4j
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;
    private final ObjectMapper objectMapper;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> start(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                               @Valid @RequestBody RunRequest request) {
        return runService.startRun(userId, request)
                .map(this::toSse);
    }

    @PostMapping("/{runId}/stop")
    public ApiResult<Void> stop(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                @PathVariable String runId) {
        runService.stopRun(userId, runId);
        return ApiResult.ok();
    }

    private ServerSentEvent<String> toSse(SseEnvelope envelope) {
        try {
            return ServerSentEvent.<String>builder()
                    .event(envelope.event())
                    .id(String.valueOf(envelope.seq()))
                    .data(objectMapper.writeValueAsString(envelope))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("SSE 事件序列化失败: {}", envelope.event(), e);
            return ServerSentEvent.<String>builder()
                    .comment("serialization failed")
                    .build();
        }
    }
}
