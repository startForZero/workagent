package com.chenxi.workagent.agent.factory;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 模型工厂（策略模式）：按 provider 构造模型实例。
 * DashScope 走原生实现；DeepSeek/Kimi/MiniMax/自定义均为 OpenAI 兼容协议，复用 openai 实现。
 * @author 辰夕
 */
@Component
public class ModelFactory {

    /** provider 常量（前端预置模板与此一一对应） */
    public static final String PROVIDER_DASHSCOPE = "dashscope";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_KIMI = "kimi";
    public static final String PROVIDER_MINIMAX = "minimax";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_CUSTOM = "custom";

    /** modelKey 分隔符：provider:model */
    public static final String MODEL_KEY_SEPARATOR = ":";

    /**
     * 构造模型实例。
     *
     * @param provider 服务商
     * @param model    模型名
     * @param baseUrl  OpenAI 兼容接口地址（dashscope 可空）
     * @param apiKey   明文 apiKey（调用方负责解密，禁止入日志）
     */
    public Model create(String provider, String model, String baseUrl, String apiKey) {
        if (PROVIDER_DASHSCOPE.equals(provider)) {
            return DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(model)
                    .stream(true)
                    .formatter(new DashScopeChatFormatter())
                    .build();
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .stream(true)
                .formatter(new OpenAIChatFormatter());
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * 组装 modelKey（provider:model）。
     */
    public String toModelKey(String provider, String model) {
        return provider + MODEL_KEY_SEPARATOR + model;
    }
}
