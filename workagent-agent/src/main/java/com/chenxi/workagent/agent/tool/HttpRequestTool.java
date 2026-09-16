package com.chenxi.workagent.agent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 内置 HTTP 调用工具（M1 基础版；SSRF 白名单/凭证注入在 M2 加固）。
 *
 * <p>HTTPS 兼容策略：完全跳过证书信任校验与主机名校验（SAN/CN），兼容企业内网
 * 自签 / 私有 CA / IP 直连的老旧证书。
 *
 * <p>实现选型：不用 java.net.http.HttpClient——其握手阶段强制套回 HTTPS 主机名
 * 校验（sslParameters 置 null 无效，只能开 JVM 级内部属性，会波及模型 SDK 请求）。
 * 改用 HttpURLConnection + 按连接设置 trust-all SSLSocketFactory 与放行的
 * HostnameVerifier，作用域仅本工具，全局 JVM 网络栈不受影响。
 * @author 辰夕
 */
public class HttpRequestTool {

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int MAX_BODY_CHARS = 20_000;
    /** 重定向最多跟随次数（防 301 环） */
    private static final int MAX_REDIRECTS = 5;
    private static final List<Integer> REDIRECT_CODES = List.of(301, 302, 303, 307, 308);

    /** 信任所有证书的 SocketFactory（仅本工具实例使用，不碰全局 truststore） */
    private final SSLSocketFactory trustAllSocketFactory;
    /** 放行一切主机名（含 IP 直连 CN-only 证书） */
    private final HostnameVerifier trustAllHostname = (hostname, session) -> true;

    public HttpRequestTool() {
        this.trustAllSocketFactory = trustAllSslContext().getSocketFactory();
    }

    /**
     * 构造一个信任所有证书的 SSLContext。仅用于 https:// 连接；http:// 不走 TLS。
     */
    private static SSLContext trustAllSslContext() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { trustAll }, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TrustAll SSLContext 失败", e);
        }
    }

    @Tool(description = "发起 HTTP/HTTPS 请求（GET/POST/PUT/DELETE/PATCH），用于调用外部接口。"
            + "HTTPS 跳过证书与主机名校验，可直连企业内网自签/私有 CA/IP 证书服务。"
            + "返回状态码与响应体（超长截断）。")
    public String http_request(
            @ToolParam(name = "method", description = "HTTP 方法：GET/POST/PUT/DELETE/PATCH") String method,
            @ToolParam(name = "url", description = "完整请求 URL，支持 http 与 https") String url,
            @ToolParam(name = "headers", description = "可选，请求头 JSON 对象", required = false)
                    Map<String, String> headers,
            @ToolParam(name = "body", description = "可选，请求体字符串", required = false) String body) {
        try {
            return executeWithRedirects(method.toUpperCase(), url, headers, body, 0);
        } catch (Exception e) {
            return "HTTP 请求失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 手动跟随重定向（HttpURLConnection 对带 body 的 POST 不自动跟）；最多 MAX_REDIRECTS 跳 */
    private String executeWithRedirects(String method, String url, Map<String, String> headers,
                                        String body, int hop) throws IOException {
        if (hop > MAX_REDIRECTS) {
            return "HTTP 请求失败: 重定向次数超过 " + MAX_REDIRECTS;
        }
        HttpURLConnection conn = openConnection(url);
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_SECONDS * 1000);
            conn.setReadTimeout(READ_TIMEOUT_SECONDS * 1000);
            // 手动跟随：默认自动跟只覆盖 GET/HEAD，POST 需自行处理
            conn.setInstanceFollowRedirects(false);
            // HttpURLConnection 原生不支持 PATCH，按惯例转 POST + X-HTTP-Method-Override
            if ("PATCH".equals(method)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                conn.setRequestMethod(method);
            }
            if (headers != null) {
                headers.forEach(conn::setRequestProperty);
            }
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = conn.getResponseCode();
            if (REDIRECT_CODES.contains(status)) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isBlank()) {
                    return "HTTP " + status + "\n" + readBody(conn, status);
                }
                // 303 及 301/302+非 GET：按浏览器惯例转 GET 丢 body；307/308 保留方法与 body
                boolean toGet = status == 303 || (status != 307 && status != 308
                        && !"GET".equals(method) && !"HEAD".equals(method));
                String nextUrl = URI.create(url).resolve(location).toString();
                return executeWithRedirects(toGet ? "GET" : method, nextUrl, headers,
                        toGet ? null : body, hop + 1);
            }

            String responseBody = readBody(conn, status);
            if (responseBody.length() > MAX_BODY_CHARS) {
                responseBody = responseBody.substring(0, MAX_BODY_CHARS) + "...(已截断)";
            }
            return "HTTP " + status + "\n" + responseBody;
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(trustAllSocketFactory);
            https.setHostnameVerifier(trustAllHostname);
        }
        return conn;
    }

    /** 4xx/5xx 走 errorStream，否则 inputStream；读不到流则空串 */
    private String readBody(HttpURLConnection conn, int status) throws IOException {
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) {
            return "";
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
