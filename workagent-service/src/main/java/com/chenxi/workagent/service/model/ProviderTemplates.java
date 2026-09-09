package com.chenxi.workagent.service.model;

import com.chenxi.workagent.agent.factory.ModelFactory;
import com.chenxi.workagent.service.model.dto.ProviderTemplate;
import java.util.List;

/**
 * 服务商预置模板常量（与 ModelFactory 的 provider 策略一一对应）。
 * @author 辰夕
 */
public final class ProviderTemplates {

    private ProviderTemplates() {
    }

    public static final List<ProviderTemplate> ALL = List.of(
            new ProviderTemplate(ModelFactory.PROVIDER_DEEPSEEK, "DeepSeek",
                    "https://api.deepseek.com/v1", "deepseek-chat"),
            new ProviderTemplate(ModelFactory.PROVIDER_KIMI, "Kimi（Moonshot）",
                    "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            new ProviderTemplate(ModelFactory.PROVIDER_MINIMAX, "MiniMax",
                    "https://api.minimax.chat/v1", "MiniMax-Text-01"),
            new ProviderTemplate(ModelFactory.PROVIDER_DASHSCOPE, "通义千问（DashScope）",
                    "", "qwen-plus"),
            new ProviderTemplate(ModelFactory.PROVIDER_OPENAI, "OpenAI",
                    "https://api.openai.com/v1", "gpt-4o-mini"),
            new ProviderTemplate(ModelFactory.PROVIDER_CUSTOM, "自定义（OpenAI 兼容）",
                    "", ""));
}
