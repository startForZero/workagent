package com.chenxi.workagent.agent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 内置 HTTP 调用工具（M1 基础版；SSRF 白名单/凭证注入在 M2 加固）。
 * HTTPS 默认走 JVM truststore 校验，禁止 TrustAll。
 * @author 辰夕
 */
public class HttpRequestTool {

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int MAX_BODY_CHARS = 20_000;

    private final HttpClient client;

    public HttpRequestTool() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(description = "发起 HTTP/HTTPS 请求（GET/POST/PUT/DELETE/PATCH），用于调用外部接口。"
            + "返回状态码与响应体（超长截断）。")
    public String http_request(
            @ToolParam(name = "method", description = "HTTP 方法：GET/POST/PUT/DELETE/PATCH") String method,
            @ToolParam(name = "url", description = "完整请求 URL，支持 https") String url,
            @ToolParam(name = "headers", description = "可选，请求头 JSON 对象", required = false)
                    Map<String, String> headers,
            @ToolParam(name = "body", description = "可选，请求体字符串", required = false) String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method.toUpperCase(), publisher);

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();
            if (responseBody.length() > MAX_BODY_CHARS) {
                responseBody = responseBody.substring(0, MAX_BODY_CHARS) + "...(已截断)";
            }
            return "HTTP " + response.statusCode() + "\n" + responseBody;
        } catch (Exception e) {
            return "HTTP 请求失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
