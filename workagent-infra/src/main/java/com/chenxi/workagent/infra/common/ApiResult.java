package com.chenxi.workagent.infra.common;

/**
 * 统一 REST 响应包络。SSE 通道不走此包络（走事件包络）。
 * @author 辰夕
 */
public record ApiResult<T>(int code, String message, T data) {

    private static final int CODE_SUCCESS = 0;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(CODE_SUCCESS, "ok", data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(CODE_SUCCESS, "ok", null);
    }

    public static ApiResult<Void> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
