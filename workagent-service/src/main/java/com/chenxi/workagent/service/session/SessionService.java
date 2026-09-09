package com.chenxi.workagent.service.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.entity.MessageDO;
import com.chenxi.workagent.infra.entity.SessionDO;
import com.chenxi.workagent.infra.mapper.MessageMapper;
import com.chenxi.workagent.infra.mapper.SessionMapper;
import com.chenxi.workagent.service.session.dto.CreateSessionRequest;
import com.chenxi.workagent.service.session.dto.MessageResponse;
import com.chenxi.workagent.service.session.dto.SessionResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 会话服务。
 * @author 辰夕
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    /** 会话状态：正常 */
    private static final int STATUS_ACTIVE = 1;
    /** 默认标题 */
    private static final String DEFAULT_TITLE = "新对话";

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;

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
                .map(m -> new MessageResponse(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
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
