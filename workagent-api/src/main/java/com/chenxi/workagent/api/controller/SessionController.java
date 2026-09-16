package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.artifact.ArtifactService;
import com.chenxi.workagent.service.artifact.dto.ArtifactResponse;
import com.chenxi.workagent.service.session.SessionService;
import com.chenxi.workagent.service.session.dto.CreateSessionRequest;
import com.chenxi.workagent.service.session.dto.MessageResponse;
import com.chenxi.workagent.service.session.dto.SessionResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final ArtifactService artifactService;

    @PostMapping
    public ApiResult<SessionResponse> create(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                             @Valid @RequestBody CreateSessionRequest request) {
        return ApiResult.ok(sessionService.create(userId, request));
    }

    @GetMapping
    public ApiResult<List<SessionResponse>> list(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId) {
        return ApiResult.ok(sessionService.list(userId));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResult<List<MessageResponse>> messages(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                                     @PathVariable("sessionId") String sessionId) {
        return ApiResult.ok(sessionService.messages(userId, sessionId));
    }

    /** 删除会话（含消息/运行/附件/产物；有活跃 run 时拒绝） */
    @DeleteMapping("/{sessionId}")
    public ApiResult<Void> delete(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                  @PathVariable("sessionId") String sessionId) {
        sessionService.delete(userId, sessionId);
        return ApiResult.ok(null);
    }

    /** 会话产物列表（右侧产物面板数据源） */
    @GetMapping("/{sessionId}/artifacts")
    public ApiResult<List<ArtifactResponse>> artifacts(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                                       @PathVariable("sessionId") String sessionId) {
        return ApiResult.ok(artifactService.list(userId, sessionId));
    }
}
