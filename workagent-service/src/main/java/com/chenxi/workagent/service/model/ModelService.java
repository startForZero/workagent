package com.chenxi.workagent.service.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.agent.factory.AgentFactory;
import com.chenxi.workagent.agent.factory.ModelFactory;
import com.chenxi.workagent.agent.factory.ModelResolver;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.UserModelDO;
import com.chenxi.workagent.infra.mapper.UserModelMapper;
import com.chenxi.workagent.service.crypto.AesGcmCryptoService;
import com.chenxi.workagent.service.model.dto.ModelSaveRequest;
import com.chenxi.workagent.service.model.dto.UserModelResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户模型管理服务（BYOK）：CRUD + 连通性测试 + ModelResolver 动态解析。
 * apiKey 一律 AES-GCM 加密存储，禁止入日志、不回显。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelService implements ModelResolver {

    /** 连通性测试超时 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    /** 启用状态 */
    private static final int ENABLED = 1;
    private static final int DISABLED = 0;

    private final UserModelMapper userModelMapper;
    private final AesGcmCryptoService cryptoService;
    private final ModelFactory modelFactory;
    private final WorkagentProperties properties;
    /** ObjectProvider 规避循环依赖：ModelService → AgentFactory → ModelResolver(=ModelService) */
    private final ObjectProvider<AgentFactory> agentFactoryProvider;

    // ---------- CRUD ----------

    public List<UserModelResponse> list(Long userId) {
        return userModelMapper.selectList(new LambdaQueryWrapper<UserModelDO>()
                        .eq(UserModelDO::getUserId, userId)
                        .orderByDesc(UserModelDO::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 添加模型：先连通性测试，通过才入库。
     */
    @Transactional
    public UserModelResponse create(Long userId, ModelSaveRequest request) {
        testConnectivity(request.provider(), request.model(), request.baseUrl(), request.apiKey());
        UserModelDO entity = new UserModelDO();
        entity.setUserId(userId);
        entity.setProvider(request.provider());
        entity.setModel(request.model());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKeyEnc(cryptoService.encrypt(request.apiKey()));
        entity.setEnabled(ENABLED);
        entity.setCreatedAt(LocalDateTime.now());
        userModelMapper.insert(entity);
        return toResponse(entity);
    }

    /**
     * 启停模型；停用后对应 Agent 实例立即失效。
     */
    @Transactional
    public void setEnabled(Long userId, Long modelId, boolean enabled) {
        UserModelDO entity = requireOwned(userId, modelId);
        entity.setEnabled(enabled ? ENABLED : DISABLED);
        userModelMapper.updateById(entity);
        if (!enabled) {
            agentFactoryProvider.getObject()
                    .evict(userId, modelFactory.toModelKey(entity.getProvider(), entity.getModel()));
        }
    }

    @Transactional
    public void delete(Long userId, Long modelId) {
        UserModelDO entity = requireOwned(userId, modelId);
        userModelMapper.deleteById(modelId);
        agentFactoryProvider.getObject()
                .evict(userId, modelFactory.toModelKey(entity.getProvider(), entity.getModel()));
    }

    /**
     * 连通性测试（「测试并保存」）：用该配置发起一次最小探测调用。
     */
    public void testConnectivity(String provider, String model, String baseUrl, String apiKey) {
        Model instance = modelFactory.create(provider, model, baseUrl, apiKey);
        Msg probe = Msg.builder()
                .name("probe")
                .role(MsgRole.USER)
                .textContent("ping")
                .build();
        try {
            instance.stream(List.of(probe), null, null)
                    .collectList()
                    .block(TEST_TIMEOUT);
        } catch (Exception e) {
            log.warn("模型连通性测试失败: provider={}, model={}, 原因={}", provider, model, e.getMessage());
            throw new BizException(ErrorCode.MODEL_TEST_FAILED);
        }
    }

    // ---------- ModelResolver ----------

    @Override
    public String effectiveModelKey(Long userId, String modelKey) {
        if (StringUtils.hasText(modelKey)) {
            return modelKey;
        }
        UserModelDO first = userModelMapper.selectOne(new LambdaQueryWrapper<UserModelDO>()
                .eq(UserModelDO::getUserId, userId)
                .eq(UserModelDO::getEnabled, ENABLED)
                .orderByDesc(UserModelDO::getCreatedAt)
                .last("LIMIT 1"));
        if (first != null) {
            return modelFactory.toModelKey(first.getProvider(), first.getModel());
        }
        return properties.getAgent().getDefaultModelKey();
    }

    @Override
    public Model resolve(Long userId, String modelKey) {
        String effective = effectiveModelKey(userId, modelKey);
        // 平台默认模型兜底
        if (effective.equals(properties.getAgent().getDefaultModelKey())) {
            UserModelDO owned = findByModelKey(userId, effective);
            if (owned == null) {
                String defaultApiKey = properties.getAgent().getDefaultModelApiKey();
                if (!StringUtils.hasText(defaultApiKey)) {
                    throw new BizException(ErrorCode.MODEL_NOT_FOUND, "尚未配置模型，请先在「设置-模型管理」中添加");
                }
                return modelFactory.create(ModelFactory.PROVIDER_DASHSCOPE,
                        stripProvider(effective), null, defaultApiKey);
            }
        }
        UserModelDO entity = findByModelKey(userId, effective);
        if (entity == null) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND);
        }
        if (!Integer.valueOf(ENABLED).equals(entity.getEnabled())) {
            throw new BizException(ErrorCode.MODEL_DISABLED);
        }
        return modelFactory.create(entity.getProvider(), entity.getModel(),
                entity.getBaseUrl(), cryptoService.decrypt(entity.getApiKeyEnc()));
    }

    // ---------- private ----------

    private UserModelDO findByModelKey(Long userId, String modelKey) {
        int sep = modelKey.indexOf(ModelFactory.MODEL_KEY_SEPARATOR);
        if (sep <= 0) {
            return null;
        }
        return userModelMapper.selectOne(new LambdaQueryWrapper<UserModelDO>()
                .eq(UserModelDO::getUserId, userId)
                .eq(UserModelDO::getProvider, modelKey.substring(0, sep))
                .eq(UserModelDO::getModel, modelKey.substring(sep + 1))
                .last("LIMIT 1"));
    }

    private String stripProvider(String modelKey) {
        int sep = modelKey.indexOf(ModelFactory.MODEL_KEY_SEPARATOR);
        return sep <= 0 ? modelKey : modelKey.substring(sep + 1);
    }

    private UserModelDO requireOwned(Long userId, Long modelId) {
        UserModelDO entity = userModelMapper.selectById(modelId);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND);
        }
        return entity;
    }

    private UserModelResponse toResponse(UserModelDO entity) {
        return new UserModelResponse(entity.getId(), entity.getProvider(), entity.getModel(),
                modelFactory.toModelKey(entity.getProvider(), entity.getModel()),
                entity.getBaseUrl(), Integer.valueOf(ENABLED).equals(entity.getEnabled()),
                entity.getCreatedAt());
    }
}
