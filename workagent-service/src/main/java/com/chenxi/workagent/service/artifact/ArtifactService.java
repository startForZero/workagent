package com.chenxi.workagent.service.artifact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.ArtifactDO;
import com.chenxi.workagent.infra.entity.SessionDO;
import com.chenxi.workagent.infra.mapper.ArtifactMapper;
import com.chenxi.workagent.infra.mapper.SessionMapper;
import com.chenxi.workagent.infra.storage.MinioStorageService;
import com.chenxi.workagent.service.artifact.dto.ArtifactResponse;
import com.chenxi.workagent.service.run.RunService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import java.io.ByteArrayInputStream;
import java.net.URLConnection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 产物服务：实现框架 ArtifactDeliveryTarget，deliver_artifact 工具产出归档到 MinIO 并落库，
 * 同时通过 RunService 向活跃 run 的 SSE 流补发 artifact.created 事件。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService implements ArtifactDeliveryTarget {

    /** RuntimeContext 中存放 runId 的 key（RunService 发起运行时写入） */
    public static final String CTX_KEY_RUN_ID = "workagentRunId";

    /** MinIO 对象路径分段名 */
    private static final String OBJECT_PREFIX = "artifacts";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioStorageService storageService;
    private final ArtifactMapper artifactMapper;
    private final SessionMapper sessionMapper;
    private final WorkagentProperties properties;
    private final RunService runService;

    /**
     * 框架 deliver_artifact 工具回调：字节归档 MinIO → wa_artifact 落库 → SSE 通知前端。
     */
    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext ctx, ArtifactDeliveryRequest request) {
        try {
            Long userId = Long.parseLong(ctx.getUserId());
            String sessionId = ctx.getSessionId();
            String runId = ctx.get(CTX_KEY_RUN_ID, String.class);
            SessionDO session = sessionMapper.selectOne(new LambdaQueryWrapper<SessionDO>()
                    .eq(SessionDO::getSessionId, sessionId));
            if (session == null || !session.getUserId().equals(userId)) {
                return ArtifactDeliveryResult.fail("会话不存在或不属于当前用户");
            }
            if (!request.force() && exists(session.getId(), request.fileName())) {
                return ArtifactDeliveryResult.conflict("同名产物已存在：" + request.fileName());
            }

            String ossPath = OBJECT_PREFIX + "/" + userId + "/" + sessionId + "/" + request.fileName();
            String contentType = guessContentType(request.fileName());
            storageService.put(properties.getMinio().getBucketArtifacts(), ossPath,
                    new ByteArrayInputStream(request.content()), request.content().length, contentType);

            ArtifactDO artifact = new ArtifactDO();
            artifact.setUserId(userId);
            artifact.setSessionId(session.getId());
            artifact.setRunId(runId);
            artifact.setFileName(request.fileName());
            artifact.setOssPath(ossPath);
            artifact.setSize((long) request.content().length);
            artifact.setContentType(contentType);
            artifact.setCreatedAt(LocalDateTime.now());
            artifactMapper.insert(artifact);
            log.info("产物归档: userId={}, sessionId={}, file={}, size={}", userId, sessionId,
                    request.fileName(), request.content().length);

            if (runId != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("artifactId", artifact.getId());
                payload.put("fileName", artifact.getFileName());
                payload.put("size", artifact.getSize());
                payload.put("contentType", artifact.getContentType());
                payload.put("description", request.description());
                runService.publishArtifactEvent(runId, payload);
            }
            return ArtifactDeliveryResult.success("产物已归档：" + request.fileName());
        } catch (Exception e) {
            log.error("产物投递失败: file={}", request.fileName(), e);
            return ArtifactDeliveryResult.fail("产物归档失败：" + e.getMessage());
        }
    }

    /** 会话产物列表（按时间倒序） */
    public List<ArtifactResponse> list(Long userId, String sessionId) {
        SessionDO session = requireOwnedSession(userId, sessionId);
        return artifactMapper.selectList(new LambdaQueryWrapper<ArtifactDO>()
                        .eq(ArtifactDO::getSessionId, session.getId())
                        .orderByDesc(ArtifactDO::getId))
                .stream()
                .map(a -> new ArtifactResponse(a.getId(), a.getFileName(), a.getSize(),
                        a.getContentType(), a.getRunId(), a.getCreatedAt()))
                .toList();
    }

    /** 生成产物下载预签名 URL（仅属主可下） */
    public String downloadUrl(Long userId, Long artifactId) {
        ArtifactDO artifact = artifactMapper.selectById(artifactId);
        if (artifact == null || !artifact.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        return storageService.presignGet(properties.getMinio().getBucketArtifacts(), artifact.getOssPath());
    }

    private boolean exists(Long sessionPk, String fileName) {
        return artifactMapper.selectCount(new LambdaQueryWrapper<ArtifactDO>()
                .eq(ArtifactDO::getSessionId, sessionPk)
                .eq(ArtifactDO::getFileName, fileName)) > 0;
    }

    private SessionDO requireOwnedSession(Long userId, String sessionId) {
        SessionDO session = sessionMapper.selectOne(new LambdaQueryWrapper<SessionDO>()
                .eq(SessionDO::getSessionId, sessionId));
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    private String guessContentType(String fileName) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : DEFAULT_CONTENT_TYPE;
    }
}
