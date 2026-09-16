package com.chenxi.workagent.service.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.enums.RunStatus;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.ArtifactDO;
import com.chenxi.workagent.infra.entity.FileDO;
import com.chenxi.workagent.infra.entity.MessageDO;
import com.chenxi.workagent.infra.entity.RunDO;
import com.chenxi.workagent.infra.entity.SessionDO;
import com.chenxi.workagent.infra.mapper.ArtifactMapper;
import com.chenxi.workagent.infra.mapper.FileMapper;
import com.chenxi.workagent.infra.mapper.MessageMapper;
import com.chenxi.workagent.infra.mapper.RunMapper;
import com.chenxi.workagent.infra.mapper.SessionMapper;
import com.chenxi.workagent.infra.storage.MinioStorageService;
import com.chenxi.workagent.service.session.dto.CreateSessionRequest;
import com.chenxi.workagent.service.session.dto.MessageResponse;
import com.chenxi.workagent.service.session.dto.SessionResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 会话服务。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** 会话状态：正常 */
    private static final int STATUS_ACTIVE = 1;
    /** 默认标题 */
    private static final String DEFAULT_TITLE = "新对话";

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final RunMapper runMapper;
    private final FileMapper fileMapper;
    private final ArtifactMapper artifactMapper;
    private final MinioStorageService minioStorageService;
    private final WorkagentProperties properties;

    @Transactional
    public SessionResponse create(Long userId, CreateSessionRequest request) {
        SessionDO entity = new SessionDO();
        entity.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        entity.setUserId(userId);
        entity.setTitle(StringUtils.hasText(request.title()) ? request.title() : DEFAULT_TITLE);
        entity.setStatus(STATUS_ACTIVE);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(entity);
        return toResponse(entity);
    }

    public List<SessionResponse> list(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<SessionDO>()
                        .eq(SessionDO::getUserId, userId)
                        .eq(SessionDO::getStatus, STATUS_ACTIVE)
                        .orderByDesc(SessionDO::getUpdatedAt))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 校验会话归属并返回 DO（RunService 使用）。
     */
    public SessionDO requireOwned(Long userId, String sessionId) {
        SessionDO entity = sessionMapper.selectOne(new LambdaQueryWrapper<SessionDO>()
                .eq(SessionDO::getSessionId, sessionId)
                .eq(SessionDO::getUserId, userId));
        if (entity == null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 会话历史消息（按时间正序），供前端切换会话时回放。
     */
    public List<MessageResponse> messages(Long userId, String sessionId) {
        SessionDO session = requireOwned(userId, sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<MessageDO>()
                        .eq(MessageDO::getSessionId, session.getId())
                        .orderByAsc(MessageDO::getId))
                .stream()
                .map(m -> new MessageResponse(m.getRole(), m.getContent(), m.getRunId(), m.getCreatedAt()))
                .toList();
    }

    /**
     * 删除会话及其全部关联数据（消息/运行/附件/产物行 + MinIO 对象尽力清理）。
     * 仅运行中的会话拒绝删除（run 会往已删会话写消息）；挂起等待确认/参数的会话允许删——
     * 挂起态本就可能永不被 resume，若拦着不放这些会话将永远无法删除。
     */
    @Transactional
    public void delete(Long userId, String sessionId) {
        SessionDO session = requireOwned(userId, sessionId);
        Long activeRuns = runMapper.selectCount(new LambdaQueryWrapper<RunDO>()
                .eq(RunDO::getSessionId, session.getId())
                .eq(RunDO::getStatus, RunStatus.RUNNING.name()));
        if (activeRuns != null && activeRuns > 0) {
            throw new BizException(ErrorCode.SESSION_HAS_ACTIVE_RUN);
        }
        Long sessionPk = session.getId();
        // 先取出 MinIO 对象路径，行删除后尽力清理对象存储
        List<String> uploadObjects = fileMapper.selectList(new LambdaQueryWrapper<FileDO>()
                        .eq(FileDO::getSessionId, sessionPk))
                .stream().map(FileDO::getOssPath).toList();
        List<String> artifactObjects = artifactMapper.selectList(new LambdaQueryWrapper<ArtifactDO>()
                        .eq(ArtifactDO::getSessionId, sessionPk))
                .stream().map(ArtifactDO::getOssPath).toList();

        messageMapper.delete(new LambdaQueryWrapper<MessageDO>().eq(MessageDO::getSessionId, sessionPk));
        runMapper.delete(new LambdaQueryWrapper<RunDO>().eq(RunDO::getSessionId, sessionPk));
        fileMapper.delete(new LambdaQueryWrapper<FileDO>().eq(FileDO::getSessionId, sessionPk));
        artifactMapper.delete(new LambdaQueryWrapper<ArtifactDO>().eq(ArtifactDO::getSessionId, sessionPk));
        sessionMapper.deleteById(sessionPk);
        log.info("会话已删除: userId={}, sessionId={}", userId, sessionId);

        WorkagentProperties.Minio minio = properties.getMinio();
        uploadObjects.forEach(o -> removeQuietly(minio.getBucketUploads(), o));
        artifactObjects.forEach(o -> removeQuietly(minio.getBucketArtifacts(), o));
    }

    /** MinIO 清理失败不阻断删除（对象无业务引用，残留由后续 GC 处理） */
    private void removeQuietly(String bucket, String objectName) {
        try {
            minioStorageService.remove(bucket, objectName);
        } catch (Exception e) {
            log.warn("删除会话残留对象失败: bucket={}, object={}, err={}", bucket, objectName, e.getMessage());
        }
    }


    /**
     * 首轮对话后更新标题与活跃时间。
     */
    @Transactional
    public void touchOnRun(Long sessionPk, String firstMessage) {
        SessionDO entity = sessionMapper.selectById(sessionPk);
        if (entity == null) {
            return;
        }
        if (DEFAULT_TITLE.equals(entity.getTitle()) && StringUtils.hasText(firstMessage)) {
            entity.setTitle(firstMessage.length() > 30 ? firstMessage.substring(0, 30) : firstMessage);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(entity);
    }

    private SessionResponse toResponse(SessionDO entity) {
        return new SessionResponse(entity.getSessionId(), entity.getTitle(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
